// shared/stores/groupApi.js (새로 생성)

const BASE_URL = "http://localhost:8000/api";

async function fetchClient(endpoint, options = {}) {
  // 실제 배포 시에는 Authorization 헤더에 토큰(JWT)을 넣거나
  // 쿠키(credentials: 'include')를 설정하는 코드가 추가되어야 합니다.
  const response = await fetch(`${BASE_URL}${endpoint}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  const data = await response.json();

  if (!response.ok) {
    throw data; // 프론트엔드가 기대하는 에러 포맷 ({ code: 'ERROR_CODE' })
  }
  return data;
}

// ── 조회 ──────────────────────────────────────────
export async function fetchMyGroups(userId) {
  return await fetchClient("/groups/me");
}

export async function fetchGroup(groupId) {
  return await fetchClient(`/groups/${groupId}`);
}

// ── 그룹 CRUD ─────────────────────────────
export async function createGroup(name, ownerId, ownerUser) {
  return await fetchClient("/groups", {
    method: "POST",
    body: JSON.stringify({ name }),
  });
}

export async function renameGroup(groupId, name) {
  return await fetchClient(`/groups/${groupId}`, {
    method: "PATCH",
    body: JSON.stringify({ name }),
  });
}

export async function deleteGroup(groupId, typedName) {
  return await fetchClient(`/groups/${groupId}`, {
    method: "DELETE",
    body: JSON.stringify({ typedName }), // 삭제 시 이름 확인용
  });
}

// ── 초대 코드 ─────────────────────────
export async function joinByCode(code, user) {
  return await fetchClient("/groups/join", {
    method: "POST",
    body: JSON.stringify({ code }),
  });
}

export async function reissueInviteCode(groupId) {
  return await fetchClient(`/groups/${groupId}/invite-code`, {
    method: "POST",
  });
}

// ── 멤버 관리 ────────────────────────
export async function kickMember(groupId, targetUserId) {
  return await fetchClient(`/groups/${groupId}/members/${targetUserId}`, {
    method: "DELETE",
  });
}

export async function leaveGroup(groupId, userId) {
  return await fetchClient(`/groups/${groupId}/members/me`, {
    method: "DELETE",
  });
}

// ── 프로젝트 ──────────────────────────────
export async function fetchProjects(groupId) {
  return await fetchClient(`/groups/${groupId}/projects`);
}

export async function createProject(groupId, input) {
  return await fetchClient(`/groups/${groupId}/projects`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export async function deleteProject(projectId, requesterRole) {
  return await fetchClient(`/projects/${projectId}`, {
    method: "DELETE",
  });
}
