import { useCallback, useEffect, useMemo, useState } from "react";
import * as api from "../api/dashboardApi";

const EMPTY_ITEMS = {};
const EMPTY_CHAINS = {};
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

  // ── 보드 파생: 블록 배열 → items(사전) / chains(Day별) / pool(후보) ──
  // 순서의 원천은 orderKey 이고 같으면 id 로 동점 처리한다(ERD: ORDER BY order_key, id).
  // 프로토타입처럼 시각(startMins)으로 정렬하지 않는다 — 시각은 표시·재계산용 파생값이다.
  const board = useMemo(() => {
    if (!snapshot) {
      return { items: EMPTY_ITEMS, chains: EMPTY_CHAINS, pool: EMPTY_ARRAY };
    }

    const sorted = [...snapshot.blocks].sort((a, b) => {
      if (a.orderKey === b.orderKey) return a.id - b.id;
      return a.orderKey < b.orderKey ? -1 : 1;
    });

    // Day 탭 수는 프로젝트 기간에서 파생한다. 기간이 없거나(둘 다 nullable)
    // 기간 밖 dayNo 를 가진 블록이 있어도 칸을 만들어 데이터를 잃지 않는다.
    const { startDate, endDate } = snapshot.project ?? {};
    const daysFromDates =
      startDate && endDate
        ? Math.floor(
            (new Date(endDate) - new Date(startDate)) / (24 * 60 * 60 * 1000),
          ) + 1
        : 0;
    const maxDayNo = sorted.reduce((max, b) => Math.max(max, b.dayNo ?? 0), 0);
    const dayCount = Math.max(daysFromDates, maxDayNo, 1);

    const items = {};
    const pool = [];
    const chains = {};
    for (let d = 1; d <= dayCount; d += 1) chains[`d${d}`] = [];

    for (const block of sorted) {
      items[block.id] = block;
      if (block.dayNo == null) pool.push(block.id);
      else chains[`d${block.dayNo}`].push(block.id);
    }

    return { items, chains, pool };
  }, [snapshot]);

  return {
    project: snapshot?.project ?? null,
    members: snapshot?.members ?? EMPTY_ARRAY,
    // 실시간 단계의 op 동기화 기준점. 지금은 소비처가 없지만 스냅샷과 함께만 얻을
    // 수 있는 값이라 여기서 보관한다.
    lastSeq: snapshot?.lastSeq ?? 0,
    items: board.items,
    chains: board.chains,
    pool: board.pool,
    status: !isValidId ? "error" : isStale ? "loading" : result.status,
    error: !isValidId ? { code: "INVALID_PROJECT_ID" } : isStale ? null : result.error,
    reload,
  };
}
