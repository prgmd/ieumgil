// features/my/api/groupApi.js
// 전역 axiosInstance를 사용한다 — baseURL·타임아웃·Authorization 헤더 자동 삽입,
// 401 시 accessToken 재발급 후 재시도까지 인터셉터가 처리해준다.
//
// 사용자 식별이 필요한 엔드포인트(/groups/me, /members/me)는 서버가 accessToken 으로
// 판별하므로 userId 를 인자로 받지 않는다.

import axiosInstance from "../../../global/api/axiosInstance";

/**
 * 백엔드 CustomResponse( { result: {...} } ) 래핑 대응.
 * 래핑 여부와 무관하게 실제 payload 만 돌려준다.
 */
function unwrap(data) {
  return data?.result ?? data;
}

/**
 * 컴포넌트/훅의 분기 로직이 기대하는 에러 형태({ code: 'XXX' })를 유지한다.
 * axios는 실패를 AxiosError로 감싸므로 백엔드 응답 본문을 꺼내 그대로 던진다.
 * 네트워크 오류처럼 응답 자체가 없으면 AxiosError를 그대로 올린다.
 */
function unwrapError(error) {
  throw error.response?.data ?? error;
}

/**
 * 서버는 그룹 식별자를 groupId 로 주지만 화면은 g.id 를 읽는다.
 * 이 경계에서 한 번만 바꿔주고 이 아래로는 id 하나로 통일한다 —
 * 응답마다 groupId/id 가 섞이면 어디선가 반드시 undefined 가 샌다.
 */
function withId(dto) {
  const { groupId, ...rest } = dto;
  return { id: groupId, ...rest };
}

/** 목록 카드 한 칸(Summary). members 는 없을 수 없지만 렌더가 length 를 읽으므로 방어한다. */
function toGroupCard(summary) {
  return { ...withId(summary), members: summary.members ?? [] };
}

// ── 조회 ──────────────────────────────────────────
export async function fetchMyGroups() {
  try {
    const { data } = await axiosInstance.get("/groups");
    return (unwrap(data) ?? []).map(toGroupCard);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 그룹 CRUD ─────────────────────────────
/**
 * 응답은 { id, name, inviteCode, inviteExpiresAt } — 목록 카드가 쓰는
 * members·tripCount 는 담겨 있지 않다. 호출부(useMyGroups)가 조립한다.
 */
export async function createGroup(name) {
  try {
    const { data } = await axiosInstance.post("/groups", { name });
    return withId(unwrap(data));
  } catch (error) {
    unwrapError(error);
  }
}

export async function renameGroup(groupId, name) {
  try {
    const { data } = await axiosInstance.patch(`/groups/${groupId}`, { name });
    return withId(unwrap(data));
  } catch (error) {
    unwrapError(error);
  }
}

export async function deleteGroup(groupId, typedName) {
  try {
    // axios의 delete 는 두 번째 인자가 config 다 — 본문은 config.data 로 감싸야
    // 실제로 전송된다. 그냥 넘기면 axios 가 모르는 옵션으로 취급해 본문 없이 나가고,
    // 백엔드의 @RequestBody 검증에서 400 이 된다.
    const { data } = await axiosInstance.delete(`/groups/${groupId}`, {
      data: { confirmName: typedName },
    });
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 초대 코드 ─────────────────────────
export async function joinByCode(code) {
  try {
    const { data } = await axiosInstance.post("/groups/join", { inviteCode: code });
    return withId(unwrap(data));
  } catch (error) {
    unwrapError(error);
  }
}
