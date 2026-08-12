import * as blockApi from "../../../features/dashboard/api/dashboardApi";
import {
  safeKeyBetween,
  neighborKeysAround,
  resolveOverlaps,
  persistMovedOffsets,
} from "../boardOrdering";
import { isTempId, isServerBlock } from "../dashboardHelpers";

export function useBlockCrud({
  items,
  setItems,
  pool,
  setPool,
  board,
  editingBlockId,
  setEditingBlockId,
  detailLocks,
  currentUser,
  projectId,
  adoptServerId,
  rollbackToServer,
  showToast,
}) {
  /**
   * @param form     폼의 현재 값(서버 필드명)
   * @param baseline 모달을 연 시점의 폼 값 — "사용자가 만진 필드"의 판정 기준.
   *                 지금의 items[targetId] 와 비교하면, 모달이 열린 사이 다른
   *                 멤버가 바꾼 필드까지 내 변경으로 잡혀 옛 값이 함께 전송되고
   *                 서버 LWW(수신 시각)가 그걸 최신으로 받아 남의 변경을 지운다.
   */
  const handleSaveBlock = async (form, baseline) => {
    const targetId = editingBlockId;
    const base = items[targetId];
    if (!base) return;

    // 남이 상세락을 쥔 사이엔 비고(detail)를 저장에서 제외한다 — 배지 도착 전
    // 창에 친 값이나 인계 직전 스냅샷이 남의 최신 비고를 덮어쓰지 않게 한다.
    // 로컬에도 form.detail 을 반영하지 않아(patched 에서도 뺀다) 서버 진실을 유지한다.
    const detailLockedByOther =
      detailLocks[targetId] != null && detailLocks[targetId] !== currentUser?.id;

    // baseline 이 없으면(구 호출부) 지금 값 기준으로 되돌아간다 — 없는 편이 낫지만
    // 최소한 저장이 통째로 막히지는 않게 한다
    const openedWith = baseline ?? {
      name: base.name ?? "",
      detail: base.detail ?? "",
      durationMin: base.dur,
      budget: base.cost,
    };
    // 폼 입력은 문자열로 온다("70") — 숫자 필드는 정규화해서 비교해야
    // "70" !== 70 이 거짓 변경으로 잡히지 않는다
    const numOf = (v) => (v === "" || v == null ? null : Number(v));
    const touched = {
      name: form.name !== openedWith.name,
      detail: form.detail !== openedWith.detail,
      dur: numOf(form.durationMin) !== numOf(openedWith.durationMin),
      cost: numOf(form.budget) !== numOf(openedWith.budget),
    };

    // 폼(서버 필드명) → 화면 블록 필드.
    // cat 은 어댑터 매핑으로 되돌린다 — toLowerCase 는 TRANSPORT→"transport" 가
    // 되어 화면의 "trans" 와 어긋난다(카테고리 왕복 파괴 버그의 원인이었다).
    const merged = {
      ...base,
      name: form.name,
      cat: blockApi.CAT_FROM_SERVER[form.category] ?? base.cat,
      sub: form.subCategory,
      address: form.address,
      // 좌표는 주소 검색(도로명 주소 → 카카오 지오코딩)이 채워 준다. 장소성
      // 카테고리(SPOT·FOOD·STAY)는 서버가 lat/lng 를 필수로 보므로(BLOCK400)
      // 여기서 흘려버리면 커스텀 블록 생성이 통째로 거절된다.
      lat: form.lat ?? base.lat ?? null,
      lng: form.lng ?? base.lng ?? null,
      detail: form.detail,
      dur: form.durationMin ? Number(form.durationMin) : base.dur,
      cost: form.budget ? Number(form.budget) : base.cost,
    };

    // ── 새 블록: 서버에 생성하고 임시 id 를 서버 blockId 로 교체한다 (2단계) ──
    if (isTempId(targetId)) {
      // 풀 맨 앞 배치를 서버에도 그대로 남기기 위해 orderKey 를 클라이언트가 만든다.
      // 미지정으로 보내면 서버가 말단 키를 부여하는데, 응답에 그 키가 없어
      // 로컬이 순서를 알 수 없게 된다.
      const firstKey =
        pool
          .filter((id) => id !== targetId)
          .map((id) => items[id]?.orderKey)
          .find((k) => k != null) ?? null;
      const orderKey = safeKeyBetween(null, firstKey);

      try {
        const created = await blockApi.createBlockWithDetail(projectId, {
          ...merged,
          startMins: null, // 커스텀 블록은 후보(POOL)로 생성된다
          orderKey,
        });

        const saved = { ...merged, id: created.blockId, orderKey };
        setItems((prev) => {
          const next = { ...prev };
          delete next[targetId];
          next[created.blockId] = saved;
          return next;
        });
        setPool((prev) =>
          prev.map((id) => (id === targetId ? created.blockId : id)),
        );
        setEditingBlockId(null);
        showToast("블록이 저장됐어요 ✓");
      } catch (e) {
        // 임시 블록과 모달을 그대로 남겨 재시도할 수 있게 한다
        showToast(
          e?.message ?? "블록을 저장하지 못했어요. 잠시 후 다시 시도해주세요.",
        );
      }
      return;
    }

    // ── 기존 블록: 사용자가 만진 필드만 PATCH /fields 배치로 저장한다 (3단계) ──
    // 판정 기준은 모달 오픈 시점(openedWith)이고, 만지지 않은 필드는 지금 서버
    // 값(base)을 그대로 둔다 — 그래야 모달이 열린 사이 도착한 남의 변경이
    // 전송에서도, 로컬 상태에서도 살아남는다.
    const patched = { ...base };
    if (touched.name) patched.name = form.name;
    if (touched.detail && !detailLockedByOther) patched.detail = form.detail;
    if (touched.dur) patched.dur = numOf(form.durationMin) ?? base.dur;
    if (touched.cost) patched.cost = numOf(form.budget) ?? base.cost;

    const changed = {};
    if (touched.name) changed.name = patched.name;
    if (touched.detail && !detailLockedByOther) changed.detail = patched.detail;
    if (touched.dur) changed.durationMin = patched.dur;
    if (touched.cost) changed.budget = patched.cost;
    // category·subCategory·address 는 보내지 않는다 — 서버 LWW 화이트리스트
    // (LWW_FIELDS: name·budget·durationMin·detail·isTimeFixed·vehicleFlag·
    // transportMeta)에 없어 BLOCK400_2 로 배치 전체가 거부된다.
    // 셋 다 "생성 시에만" 정하는 값으로 폼에서 잠갔다.
    // 종료 시각은 보내지 않는다 — 시작 오프셋 + 소요에서 파생되는 값이다.

    if (Object.keys(changed).length === 0) {
      setEditingBlockId(null); // 변경 없음 — 요청을 보내지 않는다
      return;
    }

    // 소요시간이 늘면 뒤 이웃이 밀린다 — PATCH 전에 밀린 결과를 미리 계산해 둔다.
    // 24:00 을 넘겨도 그대로 둔다 — 절대 오프셋에선 그 자리가 곧 다음 Day 다.
    // 해소는 보드 전체를 훑는다: 어느 Day 의 카드에서 열었든, 밀림은 Day 경계가
    // 아니라 다음 공백에서 멈춘다. 시각이 없는 후보(POOL)만 해소를 건너뛴다.
    let resolved = null;
    if (base.startMins != null) {
      resolved = resolveOverlaps(
        { ...items, [targetId]: patched },
        board,
        targetId,
      );
    }

    try {
      const result = await blockApi.updateBlockFields(targetId, changed);
      // 1인 모드에서 applied:false(스테일)는 나올 수 없다 — 나오면 그 자체가 조사 대상
      const stale = Object.entries(result?.applied ?? {})
        .filter(([, ok]) => !ok)
        .map(([f]) => f);
      if (stale.length > 0) {
        console.warn("[dashboard] LWW 스테일 필드 (1인 모드에서 비정상):", stale);
      }

      // 로컬 반영. 체인 블록이면 겹침 해소로 밀린 이웃들의 위치도 서버에 저장한다 —
      // 로컬만 밀면 새로고침 때 이웃들이 옛 자리로 되돌아간다(명세 320행의
      // "이동 후 시각 재계산은 클라이언트 몫" 규칙과 같은 경로, 5단계에서 재사용).
      if (resolved) {
        await persistMovedOffsets(
          resolved.newChain,
          items,
          resolved.newItems,
          targetId,
        );

        setItems(resolved.newItems);
      } else {
        setItems({ ...items, [targetId]: patched });
      }

      setEditingBlockId(null);
      showToast("블록이 저장됐어요 ✓");
    } catch (e) {
      // 모달을 열어 둔다 — 재시도하면 같은 diff 가 다시 전송된다(멱등)
      showToast(
        e?.message ?? "블록을 저장하지 못했어요. 잠시 후 다시 시도해주세요.",
      );
    }
  };

  const handleCreateCustomBlock = () => {
    const newId = `custom-${Date.now()}`;
    const newBlock = {
      id: newId,
      cat: "etc",
      sub: "",
      name: "새 일정",
      address: "",
      detail: "",
      dur: 60,
      // 후보(POOL) 블록은 시각 없는 느슨한 블록 — 시각은 체인에 놓일 때 계산된다
      startMins: null,
      lat: null,
      lng: null,
      cost: 0,
      auto: false,
    };
    setItems((prev) => ({ ...prev, [newId]: newBlock }));
    setPool((prev) => [newId, ...prev]);
    setEditingBlockId(newId);
  };

  // 블록 복사 — 같은 내용의 블록을 후보 목록 맨 위에 하나 더 만든다(숙소 여러 박 등
  // 반복 생성 편의). 시각·순서는 비우고(후보) 서버에 바로 생성한다. 모달은 열지 않아
  // 곧바로 여러 번 복사할 수 있다. 교통 블록은 구간에 묶여 있어 복사 대상에서 뺀다.
  const handleCopyBlock = (id) => {
    const src = items[id];
    if (!src || src.cat === "trans") return;
    const newId = `custom-${Date.now()}`;
    const copy = {
      ...src,
      id: newId,
      startMins: null, // 후보(POOL)
      orderKey: undefined,
      auto: false,
    };
    const nextPool = [newId, ...pool];
    setItems((prev) => ({ ...prev, [newId]: copy }));
    setPool(nextPool);

    (async () => {
      try {
        const [before, after] = neighborKeysAround(nextPool, 0, items);
        const orderKey = safeKeyBetween(before, after);
        const created = await blockApi.createBlockWithDetail(projectId, {
          ...copy,
          startMins: null,
          orderKey,
        });
        adoptServerId(newId, created.blockId, { orderKey });
        showToast("블록을 후보로 복사했어요 ⧉");
      } catch (e) {
        rollbackToServer(e);
      }
    })();
  };

  // 휴지통 드롭 — 서버 블록은 소프트 삭제(tombstone, DELETE /blocks) 후 로컬에서
  // 제거한다(4단계). 서버 확인 전에는 지우지 않는다 — 실패 시 원래 위치로 복원하는
  // 롤백을 관리하는 것보다, 확인까지의 짧은 지연을 감수하는 쪽이 단순하다.
  // 로컬 전용 블록(auto- 교통)은 요청 없이 바로 제거한다.
  const handleDeleteBlock = async (id) => {
    if (isServerBlock(id)) {
      try {
        await blockApi.deleteBlock(id);
      } catch (e) {
        showToast(
          e?.message ?? "블록을 삭제하지 못했어요. 잠시 후 다시 시도해주세요.",
        );
        return; // 블록을 그대로 둔다 — 다시 드래그하면 재시도
      }
    }

    setPool((prev) => prev.filter((x) => x !== id));
    setItems((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    showToast("블록을 삭제했어요 🗑");
  };

  // 저장 전에 모달을 닫으면 임시 블록을 남기지 않는다 (서버에도 아직 없다)
  const handleCancelEdit = () => {
    if (isTempId(editingBlockId)) {
      const tempId = editingBlockId;
      setItems((prev) => {
        const next = { ...prev };
        delete next[tempId];
        return next;
      });
      setPool((prev) => prev.filter((id) => id !== tempId));
    }
    setEditingBlockId(null);
  };

  return {
    handleSaveBlock,
    handleCreateCustomBlock,
    handleCopyBlock,
    handleDeleteBlock,
    handleCancelEdit,
  };
}
