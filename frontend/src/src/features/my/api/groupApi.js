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

// ── 조회 ──────────────────────────────────────────
export async function fetchMyGroups() {
  try {
    const { data } = await axiosInstance.get("/groups/me");
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

export async function fetchGroup(groupId) {
  try {
    const { data } = await axiosInstance.get(`/groups/${groupId}`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 그룹 CRUD ─────────────────────────────
export async function createGroup(name) {
  try {
    const { data } = await axiosInstance.post("/groups", { name });
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

export async function renameGroup(groupId, name) {
  try {
    const { data } = await axiosInstance.patch(`/groups/${groupId}`, { name });
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

export async function deleteGroup(groupId, typedName) {
  try {
    // axios의 delete 는 본문을 config.data 로 전달해야 한다 (삭제 시 이름 확인용)
    const { data } = await axiosInstance.delete(`/groups/${groupId}`, {
      data: { typedName },
    });
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 초대 코드 ─────────────────────────
export async function joinByCode(code) {
  try {
    const { data } = await axiosInstance.post("/groups/join", { code });
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

export async function reissueInviteCode(groupId) {
  try {
    const { data } = await axiosInstance.post(`/groups/${groupId}/invite-code`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 멤버 관리 ────────────────────────
export async function kickMember(groupId, targetUserId) {
  try {
    const { data } = await axiosInstance.delete(
      `/groups/${groupId}/members/${targetUserId}`,
    );
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

export async function leaveGroup(groupId) {
  try {
    const { data } = await axiosInstance.delete(
      `/groups/${groupId}/members/me`,
    );
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 프로젝트 ──────────────────────────────
export async function fetchProjects(groupId) {
  try {
    const { data } = await axiosInstance.get(`/groups/${groupId}/projects`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

export async function createProject(groupId, input) {
  try {
    const { data } = await axiosInstance.post(
      `/groups/${groupId}/projects`,
      input,
    );
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

export async function deleteProject(projectId) {
  try {
    const { data } = await axiosInstance.delete(`/projects/${projectId}`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}
