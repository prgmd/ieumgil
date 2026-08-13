import { useState, useCallback } from "react";
import * as blockApi from "../../../features/dashboard/api/dashboardApi";
import {
  buildTransportMeta,
  transitDurOf,
  transitCostOf,
  initialCandidateOf,
} from "../transitMeta";
import {
  resolveOverlaps,
  persistMovedOffsets,
  safeKeyBetween,
  neighborKeysAround,
} from "../boardOrdering";
import { isServerBlock, blocksOfDay, realBlocksOfDay } from "../dashboardHelpers";

export function useTransitPicker({
  items,
  setItems,
  board,
  projectId,
  adoptServerId,
  rollbackToServer,
  setEditingBlockId,
  showToast,
}) {
  const [isGeneratingTransport, setIsGeneratingTransport] = useState(false);

  // ── Day 전체 자동 생성 = 두 단계: ① 전 구간 후보 조회 → 통합 모달,
  //    ② 구간별 선택 적용 → 일괄 생성 ──
  // choices: "from-to" → 선택한 후보(null = 그 구간 제외)
  const [bulkTransitPicker, setBulkTransitPicker] = useState(null); // {dayKey, segments, choices}

  const regenerateAutoTransport = useCallback(
    async (dayKey) => {
      if (isGeneratingTransport || bulkTransitPicker) return;
      // 서버 계산 대상 = 그 Day 의 실블록(서버 id 보유)만. 저장 중(임시 id)·자동 생성분 제외
      const realIds = realBlocksOfDay(board, items, dayKey);
      if (realIds.length < 2) return;

      setIsGeneratingTransport(true);
      try {
        // 모든 연속 구간의 후보를 한 번의 호출로 받는다.
        // 서버는 블록을 만들지 않는다 — 생성은 모달에서 적용을 눌러야(confirmBulkTransit).
        // 출발편 기준 시각은 서버가 구간마다 from 블록의 시각에서 직접 구한다(응답의 referenceAt).
        const { segments = [] } = await blockApi.calculateTransitCandidates(
          projectId,
          realIds,
        );
        if (!segments.some((s) => s.candidates?.some((c) => c.status === "OK"))) {
          showToast("이동 가능한 경로를 찾지 못했어요.");
          return;
        }
        // 구간별 초기 선택 = 서버 추천(defaultMode) → 첫 이용 가능 후보 → 제외(null)
        // defaultMode 가 null 이면(교통수단 선호 둘 다 선택) 자동 선택하지 않는다 —
        // choices 에 키를 아예 넣지 않는다(= "제외"와 구분되는 "미선택" 상태),
        // 사용자가 카드에서 직접 골라야 한다.
        // choices[pairKey] = {candidate, departure} | null(제외) | undefined(미선택)
        // — departure 는 시외에서 고른 편(시내면 null), candidate 의 첫 편으로 초기화한다.
        const choices = {};
        segments.forEach((s) => {
          if (s.defaultMode == null) return;
          const initial = initialCandidateOf(s);
          choices[`${s.fromBlockId}-${s.toBlockId}`] = initial
            ? { candidate: initial, departure: initial.departures?.[0] ?? null }
            : null;
        });
        setBulkTransitPicker({ dayKey, segments, choices });
      } catch (e) {
        showToast(
          e?.message ?? "이동수단을 계산하지 못했어요. 잠시 후 다시 시도해주세요.",
        );
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [
      isGeneratingTransport,
      bulkTransitPicker,
      board,
      items,
      projectId,
      showToast,
    ],
  );

  // 통합 모달에서 구간 하나의 선택을 바꾼다 (choice = null 이면 그 구간 제외)
  const setBulkChoice = (pairKey, choice) => {
    setBulkTransitPicker((prev) =>
      prev ? { ...prev, choices: { ...prev.choices, [pairKey]: choice } } : prev,
    );
  };

  // 낙관 생성한 교통 블록 하나를 서버에 반영 — 양옆 실블록 사이 orderKey 를 잡아
  // createBlock 한 뒤 로컬 임시 id 를 서버 blockId 로 교체한다. bulk 는 반복 호출,
  // single 은 한 번 호출한다. (에러 처리는 각 호출부의 try/rollbackToServer 가 감싼다.)
  const persistTransitBlock = useCallback(
    async (localId, newChain, resolvedItems) => {
      const b = resolvedItems[localId];
      if (!b) return;
      const [before, after] = neighborKeysAround(
        newChain,
        newChain.indexOf(localId),
        resolvedItems,
      );
      const orderKey = safeKeyBetween(before, after);
      // transportMeta 는 이미 buildTransportMeta 로 만들어 b 에 실려 있다(...b).
      // adoptServerId 가 extra 로도 받도록 그 값을 그대로 넘긴다.
      const transportMeta = b.transportMeta;
      const created = await blockApi.createBlock(projectId, {
        ...b,
        orderKey,
        transportMeta,
      });
      adoptServerId(localId, created.blockId, { orderKey, transportMeta });
    },
    [projectId, adoptServerId],
  );

  // "적용" — 구간별 선택대로 이 Day 의 교통 블록을 일괄 재생성한다.
  // 재생성 = 기존 자동 생성분을 지우고 새로 만든다. 삭제 대상은 체인 소속으로
  // 한정한다 — 팀원이 직접 만든 교통 블록(auto 아님)은 건드리지 않는다.
  const confirmBulkTransit = useCallback(
    async () => {
      const picker = bulkTransitPicker;
      if (!picker) return;
      const { dayKey, choices, segments } = picker;
      const segmentOf = (fromId, toId) =>
        segments.find((s) => s.fromBlockId === fromId && s.toBlockId === toId);

      // 모달이 열린 사이 보드가 바뀌었을 수 있다(협업) — 지금 보드를 기준으로
      // 다시 훑고, 더 이상 인접하지 않은 구간의 선택은 자연히 버려진다(pair 키 불일치)
      const dayIds = blocksOfDay(board, items, dayKey);
      const realIds = realBlocksOfDay(board, items, dayKey);
      const oldAutoIds = dayIds.filter((id) => items[id]?.auto);
      if (realIds.length < 2) {
        setBulkTransitPicker(null);
        return;
      }

      setIsGeneratingTransport(true);
      try {
        let newItems = { ...items };
        oldAutoIds.forEach((id) => delete newItems[id]);

        // 해소는 보드 전체를 훑는다 — 이 Day 의 교통 블록이 뒤를 밀면 다음 Day 의
        // 블록까지 밀릴 수 있으므로 목록도 보드 전체여야 한다. 지운 자동 생성분을
        // 빼고, 새 교통 블록은 앞 블록 바로 뒤에 끼운다 — 같은 시각(앞 블록의 끝)에
        // 놓이는 다음 실블록보다 먼저 와야 그 실블록이 밀린다.
        const rebuilt = board.filter((id) => !oldAutoIds.includes(id));
        const createdLocalIds = [];
        realIds.forEach((id, i) => {
          if (i < realIds.length - 1) {
            const chosen = choices[`${id}-${realIds[i + 1]}`];
            if (chosen?.candidate?.status !== "OK") return; // 제외했거나 모르는 구간 — 만들지 않는다
            const info = {
              mode: chosen.candidate.label || chosen.candidate.mode,
              dur: transitDurOf(chosen.candidate, chosen.departure),
              cost: transitCostOf(chosen.candidate, chosen.departure),
            };
            const newId = `auto-${dayKey}-${id}-${i}`;
            newItems[newId] = {
              id: newId,
              cat: "trans",
              sub: info.mode,
              name: `${newItems[id]?.name || ""} 다음 이동`,
              address: "",
              dur: info.dur,
              cost: info.cost,
              auto: true,
              autoDay: dayKey,
              startMins: newItems[id].startMins + newItems[id].dur,
              transportMeta: buildTransportMeta(
                segmentOf(id, realIds[i + 1]),
                chosen.candidate,
                chosen.departure,
              ),
            };
            rebuilt.splice(rebuilt.indexOf(id) + 1, 0, newId);
            createdLocalIds.push(newId);
          }
        });
        // 만들 것도, 지울 것도 없으면 보드를 건드리지 않는다
        if (createdLocalIds.length === 0 && oldAutoIds.length === 0) {
          setBulkTransitPicker(null);
          return;
        }

        const { newItems: resolvedItems, newChain } = resolveOverlaps(
          newItems,
          rebuilt,
          null,
        );

        setBulkTransitPicker(null);

        // 낙관 적용 — 교통 블록이 뒤를 밀어 24:00 을 넘겨도 그대로 둔다.
        // 절대 오프셋에선 1440 을 넘긴 자리가 곧 다음 Day 다(쪼갤 게 없다).
        setItems(resolvedItems);

        // ── 서버 반영 (5.5단계): 기존 생성분 삭제 → 밀린 실블록 시각 저장 →
        //    새 교통 블록 생성 → 로컬 임시 id 를 서버 blockId 로 교체 ──
        try {
          await Promise.all(
            oldAutoIds
              .filter(isServerBlock)
              .map((id) => blockApi.deleteBlock(id)),
          );

          // 새 교통 블록은 아래에서 만든다 — persistMovedOffsets 의 서버 블록
          // 필터가 로컬 임시 id 를 이미 걸러 준다
          await persistMovedOffsets(newChain, items, resolvedItems, null);

          // 각 교통 블록의 경계는 양옆 실블록 — 아직 로컬인 다른 교통 블록은
          // neighborKeysAround 가 건너뛴다
          for (const localId of createdLocalIds) {
            await persistTransitBlock(localId, newChain, resolvedItems);
          }
        } catch (e) {
          rollbackToServer(e);
        }
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [
      bulkTransitPicker,
      board,
      items,
      rollbackToServer,
      setItems,
      persistTransitBlock,
    ],
  );

  // ── 구간 "이동 추가" = 두 단계: ① 후보 조회 → 선택 모달, ② 선택 → 블록 생성 ──
  // 어떤 수단으로 갈지는 사용자가 고른다 — 서버 추천(defaultMode)은 표시만 한다.
  const [transitPicker, setTransitPicker] = useState(null); // {dayKey, currentId, nextId, segment, defaultMode, candidates, chosenCandidate, chosenDeparture}

  const handleAddSingleTransport = useCallback(
    async (dayKey, currentId, nextId) => {
      if (isGeneratingTransport || transitPicker) return;

      // 보드 소속 판정은 오프셋 하나다 — 시각이 없으면 후보(POOL)라 이을 구간이 없다
      if (items[currentId]?.startMins == null) return;
      // 서버 계산은 서버 블록 id 로만 가능하다 — 저장 중(임시 id)이면 잠시 뒤에
      if (!isServerBlock(currentId) || !isServerBlock(nextId)) {
        showToast("블록 저장이 끝난 뒤 다시 시도해주세요.");
        return;
      }

      setIsGeneratingTransport(true);
      try {
        // 두 블록 사이 한 구간만 계산 — blockIds 에 그 둘만 넘긴다.
        // 출발편 기준 시각은 서버가 from 블록의 시각에서 직접 구한다(응답의 referenceAt).
        const { segments = [] } = await blockApi.calculateTransitCandidates(
          projectId,
          [currentId, nextId],
        );
        const segment = segments[0];
        const candidates = segment?.candidates ?? [];
        if (!candidates.some((c) => c.status === "OK")) {
          showToast("두 장소 사이의 경로를 찾지 못했어요.");
          return;
        }
        // defaultMode 가 null 이면(교통수단 선호 둘 다 선택) 자동 선택하지 않는다 —
        // 사용자가 카드에서 직접 골라야 한다.
        const initialCandidate = initialCandidateOf(segment);
        // 생성하지 않고 선택 모달을 연다 — 생성은 confirmTransitChoice 가 한다
        setTransitPicker({
          dayKey,
          currentId,
          nextId,
          segment,
          defaultMode: segment.defaultMode,
          candidates,
          chosenCandidate: initialCandidate,
          chosenDeparture: initialCandidate?.departures?.[0] ?? null,
        });
      } catch (e) {
        showToast(
          e?.message ?? "이동수단을 계산하지 못했어요. 잠시 후 다시 시도해주세요.",
        );
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [isGeneratingTransport, transitPicker, items, projectId, showToast],
  );

  // 피커 상태에서 고른 후보/편만 바꾸는 updater 팩토리 — 생성/재선택 피커가 공유한다.
  const chooseCandidateOn = (setter) => (c) =>
    setter((prev) =>
      prev
        ? { ...prev, chosenCandidate: c, chosenDeparture: c.departures?.[0] ?? null }
        : prev,
    );

  // 피커에서 다른 후보/편을 고른다 (아직 생성하지 않는다 — confirmTransitChoice 가 한다)
  const setTransitPickerCandidate = chooseCandidateOn(setTransitPicker);

  // 선택 모달에서 "확인"을 누르면 그 구간에 교통 블록을 만든다 (기존 5.5단계 경로)
  const confirmTransitChoice = useCallback(
    async () => {
      const picker = transitPicker;
      setTransitPicker(null);
      const chosen = picker?.chosenCandidate;
      if (!picker || chosen?.status !== "OK") return;

      const { dayKey, currentId } = picker;
      // 해소는 보드 전체를 훑으므로 목록도 보드 전체다 — 새 교통 블록은 앞 블록
      // 바로 뒤에 끼운다(같은 시각에 놓이는 다음 블록보다 먼저 와야 그쪽이 밀린다).
      const currentChain = [...board];
      const insertIdx = currentChain.indexOf(currentId);
      // 모달이 열린 사이 보드가 바뀌었을 수 있다(협업) — 자리가 사라졌으면 중단
      if (insertIdx === -1 || items[currentId]?.startMins == null) {
        showToast("구간이 바뀌어 추가하지 못했어요. 다시 시도해주세요.");
        return;
      }

      const info = {
        mode: chosen.label || chosen.mode,
        dur: transitDurOf(chosen, picker.chosenDeparture),
        cost: transitCostOf(chosen, picker.chosenDeparture),
      };

      setIsGeneratingTransport(true);
      try {
        const newId = `auto-${dayKey}-${currentId}-${Date.now()}`;

        let newItems = { ...items };
        newItems[newId] = {
          id: newId,
          cat: "trans",
          sub: info.mode,
          name: `${items[currentId]?.name || "이전 장소"} 다음 이동`,
          address: "",
          dur: info.dur,
          cost: info.cost,
          auto: true,
          autoDay: dayKey,
          startMins: items[currentId].startMins + items[currentId].dur,
          transportMeta: buildTransportMeta(
            picker.segment,
            chosen,
            picker.chosenDeparture,
          ),
        };
        currentChain.splice(insertIdx + 1, 0, newId);

        const { newItems: resolvedItems, newChain } = resolveOverlaps(
          newItems,
          currentChain,
          null,
        );

        // 낙관 적용 — 교통 블록이 뒤를 밀어 24:00 을 넘겨도 그대로 둔다.
        // 절대 오프셋에선 1440 을 넘긴 자리가 곧 다음 Day 다(쪼갤 게 없다).
        setItems(resolvedItems);

        // ── 서버 반영 (5.5단계): 밀린 이웃 위치 저장 → 생성 → id 교체 ──
        try {
          // 새 교통 블록은 아래에서 만든다 — persistMovedOffsets 의 서버 블록
          // 필터가 로컬 임시 id 를 이미 걸러 준다
          await persistMovedOffsets(newChain, items, resolvedItems, null);

          await persistTransitBlock(newId, newChain, resolvedItems);
        } catch (e) {
          rollbackToServer(e);
        }
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [
      transitPicker,
      items,
      board,
      rollbackToServer,
      showToast,
      setItems,
      persistTransitBlock,
    ],
  );

  // ── 교통 블록 편집 재선택 — 저장된 candidates 스냅샷으로 피커를 재조회 없이 연다 ──
  // 생성 흐름(transitPicker)과 상태를 공유하지 않는다 — "생성 후 자리에 삽입" 로직과
  // 얽히면 오히려 복잡해진다(계획 Task 7).
  const [transportReselectPicker, setTransportReselectPicker] = useState(null); // {blockId, candidates, chosenCandidate, chosenDeparture}

  const handleReselectTransport = (block) => {
    const candidates = block.transportMeta?.candidates;
    if (!candidates || candidates.length === 0) {
      showToast("다시 계산할 후보가 없어요. 삭제 후 새로 만들어주세요.");
      return;
    }
    const chosenMode = block.transportMeta?.chosen?.mode;
    const initialCandidate =
      candidates.find((c) => c.mode === chosenMode) ?? candidates[0];
    setTransportReselectPicker({
      blockId: block.id,
      candidates,
      chosenCandidate: initialCandidate,
      chosenDeparture: initialCandidate?.departures?.[0] ?? null,
    });
  };

  const setReselectCandidate = chooseCandidateOn(setTransportReselectPicker);

  // "저장" — 같은 블록에서 선택만 바꾼다(재생성 없음).
  // transportMeta 뿐 아니라 소요(durationMin)·종료시각·비용(budget)까지 PATCH 해야
  // 새로고침 후에도 유지된다(예전엔 meta 만 보내 소요·비용이 로컬에만 남았다).
  // 소요가 바뀌면 이웃이 밀린다 — 저장 경로(handleSaveBlock)와 같은
  // 겹침 해소 + 밀린 이웃 위치 저장을 그대로 태운다.
  const applyReselectTransport = useCallback(async () => {
    const picker = transportReselectPicker;
    const chosen = picker?.chosenCandidate;
    if (!picker || chosen?.status !== "OK") return;
    const block = items[picker.blockId];
    if (!block) {
      setTransportReselectPicker(null);
      return; // 모달이 열린 사이 삭제됨(협업)
    }

    const newMeta = {
      ...buildTransportMeta(
        // segment 는 원래 스냅샷의 segment 메타를 그대로 유지 — 재조회하지 않으므로
        // referenceAt 등은 처음 계산 시점 그대로다. 이 segment 조각에는 candidates 가
        // 없어 buildTransportMeta 결과가 빈 배열이 되므로 원래 스냅샷으로 덮어쓴다.
        block.transportMeta?.segment,
        chosen,
        picker.chosenDeparture,
      ),
      candidates: picker.candidates,
    };
    const newDur = transitDurOf(chosen, picker.chosenDeparture);
    const newCost = transitCostOf(chosen, picker.chosenDeparture);
    const merged = { ...block, dur: newDur, cost: newCost, transportMeta: newMeta };

    // 보드 위 블록이면 소요 변경이 이웃을 민다 — 저장 전에 밀린 결과를 계산해 둔다.
    // 24:00 을 넘겨도 그대로 둔다 — 절대 오프셋에선 그 자리가 곧 다음 Day 다.
    let resolved = null;
    if (block.startMins != null) {
      resolved = resolveOverlaps(
        { ...items, [block.id]: merged },
        board,
        block.id,
      );
    }

    setTransportReselectPicker(null);
    // 뒤에 열려 있는 편집 폼도 닫는다 — 폼이 옛 소요·비용을 들고 있어서,
    // 그대로 두면 사용자가 폼 저장을 눌러 방금 바꾼 값을 되돌려버린다
    setEditingBlockId(null);
    try {
      // 종료 시각은 보내지 않는다 — 시작 오프셋 + 소요에서 파생되는 값이다
      await blockApi.updateBlockFields(picker.blockId, {
        durationMin: newDur,
        budget: newCost,
        transportMeta: newMeta,
      });

      if (resolved) {
        await persistMovedOffsets(
          resolved.newChain,
          items,
          resolved.newItems,
          block.id,
        );
        setItems(resolved.newItems);
      } else {
        setItems((prev) =>
          prev[block.id] ? { ...prev, [block.id]: merged } : prev,
        );
      }
      showToast("이동 수단을 바꿨어요 ✓");
    } catch (e) {
      showToast(e?.message ?? "저장하지 못했어요. 잠시 후 다시 시도해주세요.");
    }
  }, [transportReselectPicker, items, board, showToast, setEditingBlockId, setItems]);

  return {
    isGeneratingTransport,
    regenerateAutoTransport,
    bulkTransitPicker,
    setBulkTransitPicker,
    setBulkChoice,
    confirmBulkTransit,
    handleAddSingleTransport,
    transitPicker,
    setTransitPicker,
    setTransitPickerCandidate,
    confirmTransitChoice,
    transportReselectPicker,
    setTransportReselectPicker,
    handleReselectTransport,
    setReselectCandidate,
    applyReselectTransport,
  };
}
