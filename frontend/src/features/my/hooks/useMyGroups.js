import { useCallback, useEffect, useState } from "react";
import * as api from "../api/groupApi";

const EMPTY = [];

/** 목록 카드의 members 는 서버의 MemberAvatar 모양이다 — 직접 조립할 때도 같게 맞춘다. */
function toMemberAvatar(user) {
  return {
    memberId: user.id,
    nickname: user.nickname,
    profileImg: user.profileImg,
  };
}

/**
 * 개인 페이지의 "내 그룹" 목록을 소유하는 훅.
 * 전역 스토어를 두지 않고 이 훅을 호출한 컴포넌트가 목록을 소유한다 —
 * 페이지를 벗어나면 상태도 함께 사라지므로 예전 데이터가 남아 보일 일이 없다.
 *
 * 결과에 어느 userId 의 것인지를 함께 담아두고 렌더 시점에 비교한다
 * (useGroupDetail 과 같은 방식).
 *
 * @param {{id: number, nickname: string, profileImg: string}|null|undefined} currentUser
 *        로그인 사용자. 목록 자체는 서버가 토큰으로 판별하지만(GET /groups),
 *        계정이 바뀌면 다시 조회해야 하므로 의존성으로 받는다. 그룹 생성 직후
 *        카드를 조립할 때 "유일한 멤버"로도 쓰인다.
 */
export function useMyGroups(currentUser) {
  const userId = currentUser?.id;

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

  /**
   * 생성 응답(Created)에는 목록 카드가 쓰는 members·tripCount 가 없다.
   * 그렇다고 목록을 다시 조회할 이유도 없다 — 서버가 그룹 저장과 생성자 멤버 등록을
   * 한 트랜잭션으로 처리하므로(GroupCommandServiceImpl.createGroup) 생성 직후 값은
   * "멤버는 나 혼자, 여행은 0개"로 확정돼 있다. 왕복을 한 번 더 쓴다고 알아낼 게 없다.
   */
  const createGroup = useCallback(
    async (name) => {
      const created = await api.createGroup(name);

      const card = {
        id: created.id,
        name: created.name,
        memberCount: 1,
        tripCount: 0,
        members: currentUser ? [toMemberAvatar(currentUser)] : [],
      };
      setResult((prev) => ({ ...prev, groups: [card, ...prev.groups] }));

      return created; // 호출부가 초대코드 공유 단계에서 inviteCode 를 쓴다
    },
    [currentUser],
  );

  const renameGroup = useCallback(async (groupId, name) => {
    const updated = await api.renameGroup(groupId, name);
    // 응답(Updated)은 id·name 뿐이라 통째로 갈아끼우면 members·tripCount 가 사라진다.
    setResult((prev) => ({
      ...prev,
      groups: prev.groups.map((g) =>
        g.id === groupId ? { ...g, name: updated.name } : g,
      ),
    }));
  }, []);

  const deleteGroup = useCallback(async (groupId, typedName) => {
    await api.deleteGroup(groupId, typedName);
    setResult((prev) => ({
      ...prev,
      groups: prev.groups.filter((g) => g.id !== groupId),
    }));
  }, []);

  /**
   * 입장 응답(Joined)은 id·name 뿐이고, 남이 만든 그룹이라 멤버 수·여행 수를
   * 추측할 방법이 없다. 목록에 반쪽짜리 카드를 넣지 않는다 — 호출부가 성공 즉시
   * 그룹 페이지로 이동하고, 개인 페이지로 돌아오면 이 훅이 다시 조회한다.
   * (useGroupDetail.leaveGroup 과 같은 판단)
   */
  const joinByCode = useCallback((code) => api.joinByCode(code), []);

  return {
    groups: userId && !isStale ? result.groups : EMPTY,
    status: !userId ? "idle" : isStale ? "loading" : result.status,
    createGroup,
    renameGroup,
    deleteGroup,
    joinByCode,
  };
}
