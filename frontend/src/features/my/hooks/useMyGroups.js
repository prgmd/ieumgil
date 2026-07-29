import { useCallback, useEffect, useState } from "react";
import * as api from "../api/groupApi";

const EMPTY = [];

/**
 * 개인 페이지의 "내 그룹" 목록을 소유하는 훅.
 * 전역 스토어를 두지 않고 이 훅을 호출한 컴포넌트가 목록을 소유한다 —
 * 페이지를 벗어나면 상태도 함께 사라지므로 예전 데이터가 남아 보일 일이 없다.
 *
 * 결과에 어느 userId 의 것인지를 함께 담아두고 렌더 시점에 비교한다
 * (useGroupDetail 과 같은 방식).
 *
 * @param {number|undefined} userId 로그인 사용자 id. 목록 자체는 서버가 토큰으로
 *        판별하지만(GET /groups/me), 계정이 바뀌면 다시 조회해야 하므로 의존성으로 받는다.
 */
export function useMyGroups(userId) {
  const [result, setResult] = useState({
    userId: null,
    groups: EMPTY,
    status: "idle", // idle | loaded | error
  });

  useEffect(() => {
    if (!userId) return;

    // 언마운트·userId 변경 이후 늦게 도착한 응답을 무시한다.
    let cancelled = false;

    api
      .fetchMyGroups()
      .then((list) => {
        if (cancelled) return;
        setResult({ userId, groups: list ?? EMPTY, status: "loaded" });
      })
      .catch(() => {
        if (cancelled) return;
        setResult({ userId, groups: EMPTY, status: "error" });
      });

    return () => {
      cancelled = true;
    };
  }, [userId]);

  const isStale = result.userId !== userId;

  // ── mutation: 서버 응답으로 로컬 목록만 갱신한다(전체 재조회 없이) ──
  const createGroup = useCallback(async (name) => {
    const group = await api.createGroup(name);
    setResult((prev) => ({ ...prev, groups: [group, ...prev.groups] }));
    return group; // 호출부에서 초대코드 공유 단계로 넘어가는 데 사용
  }, []);

  const renameGroup = useCallback(async (groupId, name) => {
    const updated = await api.renameGroup(groupId, name);
    setResult((prev) => ({
      ...prev,
      groups: prev.groups.map((g) => (g.id === groupId ? updated : g)),
    }));
  }, []);

  const deleteGroup = useCallback(async (groupId, typedName) => {
    await api.deleteGroup(groupId, typedName);
    setResult((prev) => ({
      ...prev,
      groups: prev.groups.filter((g) => g.id !== groupId),
    }));
  }, []);

  const joinByCode = useCallback(async (code) => {
    const group = await api.joinByCode(code);
    setResult((prev) => ({ ...prev, groups: [group, ...prev.groups] }));
    return group;
  }, []);

  return {
    groups: userId && !isStale ? result.groups : EMPTY,
    status: !userId ? "idle" : isStale ? "loading" : result.status,
    createGroup,
    renameGroup,
    deleteGroup,
    joinByCode,
  };
}
