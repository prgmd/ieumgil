import { useCallback, useEffect, useState } from "react";
import * as api from "../api/groupPageApi";

const INVALID_ID_ERROR = { code: "INVALID_GROUP_ID" };

/**
 * URL 의 groupId 만으로 그룹 상세(멤버·초대코드 포함)를 조회하는 훅.
 * 새로고침·딥링크·내부 이동이 모두 같은 경로를 타므로 "스토어에 있으면 재사용,
 * 없으면 조회" 같은 분기가 없다.
 *
 * 조회 결과에 어느 groupId 의 것인지를 함께 담아두고, 렌더 시점에 현재 groupId 와
 * 비교한다 — groupId 가 바뀐 직후 이전 그룹의 이름·멤버·초대코드가 잠깐 보이는 일을
 * 막기 위함. (effect 에서 상태를 초기화하는 방식은 그 사이에 한 번 더 렌더된다.)
 *
 * @param {number} groupId Number(useParams().groupId) — 잘못된 URL 이면 NaN 일 수 있다.
 */
export function useGroupDetail(groupId) {
  // /groups/abc 처럼 숫자가 아닌 경로로 들어온 경우 요청 자체를 보내지 않는다.
  const isValidId = Number.isInteger(groupId);

  const [result, setResult] = useState({
    groupId: null,
    group: null,
    status: "idle", // idle | loaded | error
    error: null,
  });

  useEffect(() => {
    if (!isValidId) return;

    // 언마운트·groupId 변경 이후 늦게 도착한 응답을 무시한다.
    let cancelled = false;

    api
      .fetchGroup(groupId)
      .then((data) => {
        if (cancelled) return;
        setResult({ groupId, group: data, status: "loaded", error: null });
      })
      .catch((e) => {
        if (cancelled) return;
        setResult({ groupId, group: null, status: "error", error: e });
      });

    return () => {
      cancelled = true;
    };
  }, [groupId, isValidId]);

  // 아직 이번 groupId 의 응답이 오지 않았다면 로딩으로 취급한다.
  const isStale = result.groupId !== groupId;

  const reissueInviteCode = useCallback(async () => {
    const { inviteCode, inviteExpiresAt } = await api.reissueInviteCode(groupId);
    setResult((prev) =>
      prev.group
        ? { ...prev, group: { ...prev.group, inviteCode, inviteExpiresAt } }
        : prev,
    );
  }, [groupId]);

  // 나간 뒤에는 호출부가 개인 페이지로 이동하므로 여기서 상태를 손대지 않는다.
  const leaveGroup = useCallback(() => api.leaveGroup(groupId), [groupId]);

  return {
    group: isValidId && !isStale ? result.group : null,
    status: !isValidId ? "error" : isStale ? "loading" : result.status,
    error: !isValidId ? INVALID_ID_ERROR : isStale ? null : result.error,
    reissueInviteCode,
    leaveGroup,
  };
}
