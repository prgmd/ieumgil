import { useCallback, useEffect, useMemo, useState } from "react";
import * as api from "../api/dashboardApi";

const EMPTY_ITEMS = {};
const EMPTY_ARRAY = [];

/**
 * 대시보드 스냅샷을 소유하는 훅 (0단계 — 조회 골격).
 *
 * useGroupDetail 과 같은 규칙 — 결과에 projectId 를 함께 담아두고 렌더 시점에
 * 현재 projectId 와 비교해, 이전 프로젝트의 보드가 잠깐 보이는 일을 막는다.
 *
 * 블록 변경(생성·수정·이동·삭제) mutation 은 연동 단계(2~5단계)에서 여기에 붙는다.
 * 실시간 단계에서는 lastSeq 를 기준으로 op 구독·갭 복구가 이 훅에 얹힌다.
 *
 * @param {number} projectId Number(useParams().projectId) — 잘못된 URL 이면 NaN 일 수 있다.
 */
export function useDashboard(projectId) {
  const isValidId = Number.isInteger(projectId);

  const [result, setResult] = useState({
    projectId: null,
    snapshot: null,
    status: "idle", // idle | loaded | error
    error: null,
  });

  // 재조회 트리거 — 에러 후 재시도, (실시간 단계에서) 재연결 스냅샷 재로딩에 쓴다.
  const [reloadKey, setReloadKey] = useState(0);
  const reload = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    if (!isValidId) return;

    // 언마운트·projectId 변경 이후 늦게 도착한 응답을 무시한다.
    let cancelled = false;

    api
      .fetchSnapshot(projectId)
      .then((snapshot) => {
        if (cancelled) return;
        setResult({ projectId, snapshot, status: "loaded", error: null });
      })
      .catch((e) => {
        if (cancelled) return;
        setResult({ projectId, snapshot: null, status: "error", error: e });
      });

    return () => {
      cancelled = true;
    };
  }, [projectId, isValidId, reloadKey]);

  const isStale = result.projectId !== projectId;
  const snapshot = isValidId && !isStale ? result.snapshot : null;

  // ── 보드 파생: 블록 배열 → items(사전) / pool(후보) ──
  // 보드 소속은 시각(startMins — Day 1 00:00 기준 절대 분) 하나가 정한다. 값이
  // 있으면 시간축 위, 없으면 후보(POOL)다. Day 별 묶음은 저장하지 않는다 —
  // 화면에서 필요할 때 오프셋에서 유도한다(dashboardHelpers 의 blocksOfDay).
  // 후보의 순서만 여기서 정한다: 시각이 없어 손 정렬(orderKey)이 유일한 근거다.
  const board = useMemo(() => {
    if (!snapshot) {
      return { items: EMPTY_ITEMS, pool: EMPTY_ARRAY };
    }

    const sorted = [...snapshot.blocks].sort((a, b) => {
      // 시각 없는 후보(POOL)는 뒤로 몰아 두고 자기들끼리 orderKey 손 정렬을 지킨다.
      // (∞ 끼리는 빼면 NaN 이라 같은 값이면 먼저 걸러 낸다)
      const at = a.startMins ?? Number.POSITIVE_INFINITY;
      const bt = b.startMins ?? Number.POSITIVE_INFINITY;
      if (at !== bt) return at - bt;
      if (a.orderKey !== b.orderKey) return a.orderKey < b.orderKey ? -1 : 1;
      return a.id - b.id;
    });

    const items = {};
    const pool = [];

    for (const block of sorted) {
      items[block.id] = block;
      if (block.startMins == null) pool.push(block.id);
    }

    return { items, pool };
  }, [snapshot]);

  return {
    project: snapshot?.project ?? null,
    members: snapshot?.members ?? EMPTY_ARRAY,
    // 실시간 단계의 op 동기화 기준점. 지금은 소비처가 없지만 스냅샷과 함께만 얻을
    // 수 있는 값이라 여기서 보관한다.
    lastSeq: snapshot?.lastSeq ?? 0,
    items: board.items,
    pool: board.pool,
    status: !isValidId ? "error" : isStale ? "loading" : result.status,
    error: !isValidId ? { code: "INVALID_PROJECT_ID" } : isStale ? null : result.error,
    reload,
  };
}
