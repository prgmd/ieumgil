import axiosInstance from "../../../global/api/axiosInstance";
import { fetchMyGroups } from "../../my/api/groupApi";

function unwrap(data) {
  return data?.result ?? data;
}

function unwrapError(error) {
  throw error.response?.data ?? error;
}

/** 내 그룹 목록에 없는 groupId — 없는 그룹이거나 내가 멤버가 아닌 그룹이다. */
const NOT_MY_GROUP_ERROR = { code: "GROUP_NOT_FOUND" };

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

export async function fetchProjects(groupId) {
  try {
    const { data } = await axiosInstance.get(`/groups/${groupId}/projects`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/**
 * 프로젝트 부분 수정. PATCH /projects/{projectId} 는 보낸 필드만 반영한다
 * (ProjectReqDTO.Update — name·startDate·endDate).
 *
 * 응답은 { projectId, name, startDate, endDate, movedToPool } 이다.
 * movedToPool 은 기간이 줄어 후보로 밀려난 블록 id 목록으로, 대시보드가 블록을
 * 서버에 저장하기 전까지는 항상 빈 배열이다.
 */
export async function updateProject(projectId, patch) {
  try {
    const { data } = await axiosInstance.patch(`/projects/${projectId}`, patch);
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

export async function reissueInviteCode(groupId) {
  try {
    const { data } = await axiosInstance.post(`/groups/${groupId}/invite-code`);
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

export async function fetchMembers(groupId) {
  try {
    const { data } = await axiosInstance.get(`/groups/${groupId}/members`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/**
 * 그룹 상세. GET /groups/{groupId} 는 백엔드에 없다 — 상세 화면이 필요한
 * 세 가지(그룹명·초대코드·멤버)가 두 엔드포인트에 나뉘어 있어 여기서 합친다.
 *
 *   그룹명            → GET /groups        (Summary 에만 있다)
 *   초대코드·멤버      → GET /groups/{id}/members
 *
 * 병렬로 던지므로 지연은 둘 중 느린 쪽이다. 호출부(useGroupDetail)는 상세를
 * 한 번에 받는 것처럼 쓰고, 엔드포인트가 나뉘어 있다는 사실은 이 안에 가둔다.
 */
export async function fetchGroup(groupId) {
  const [myGroups, detail] = await Promise.all([
    fetchMyGroups(),
    fetchMembers(groupId),
  ]);

  // 목록에 없으면 없는 그룹이거나 비멤버다. (/members 의 @GroupMember 가 먼저
  // 403 을 던지는 경우가 많지만, 소프트 삭제된 그룹처럼 목록에서만 걸리는 경우도 있다.)
  const mine = myGroups.find((g) => g.id === groupId);
  if (!mine) {
    throw NOT_MY_GROUP_ERROR;
  }

  return { id: groupId, name: mine.name, ...detail };
}