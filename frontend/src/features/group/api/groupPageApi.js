import axiosInstance from "../../../global/api/axiosInstance";

function unwrap(data) {
  return data?.result ?? data;
}

function unwrapError(error) {
  throw error.response?.data ?? error;
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

export async function fetchProjects(groupId) {
  try {
    const { data } = await axiosInstance.get(`/groups/${groupId}/projects`);
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

export async function fetchGroup(groupId) {
  try {
    const { data } = await axiosInstance.get(`/groups/${groupId}`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}