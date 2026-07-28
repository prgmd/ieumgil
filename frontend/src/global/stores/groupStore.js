import { create } from "zustand";
import * as api from "../../features/group/api/GroupApi";

/**
 * groups: 개인페이지용 요약 목록
 * currentGroup: 그룹페이지에 진입했을 때 상세 조회한 결과 (groupId로 다시 fetch)
 * projects: 현재 그룹의 프로젝트 목록
 *
 * 라우트가 바뀔 때 location.state로 객체를 넘기지 않고, groupId 파라미터로
 * 이 스토어를 통해 다시 조회하는 패턴을 쓴다 — 새로고침/딥링크에도 안전하게.
 */
export const useGroupStore = create((set, get) => ({
  groups: [],
  groupsStatus: "idle",

  currentGroup: null,
  currentGroupStatus: "idle",
  currentGroupError: null,

  projects: [],
  projectsStatus: "idle",

  // ── 개인 페이지 ──
  loadMyGroups: async (userId) => {
    set({ groupsStatus: "loading" });
    const groups = await api.fetchMyGroups(userId);
    set({ groups, groupsStatus: "loaded" });
  },

  createGroup: async (name, creator) => {
    const group = await api.createGroup(name, creator);
    set({ groups: [group, ...get().groups] });
    return group; // 호출부에서 초대코드 공유 모달을 띄우는 데 사용
  },

  renameGroupLocal: async (groupId, name) => {
    const updated = await api.renameGroup(groupId, name);
    set({ groups: get().groups.map((g) => (g.id === groupId ? updated : g)) });
  },

  deleteGroupLocal: async (groupId, typedName) => {
    await api.deleteGroup(groupId, typedName);
    set({ groups: get().groups.filter((g) => g.id !== groupId) });
  },

  joinByCode: async (code, user) => {
    const group = await api.joinByCode(code, user);
    set({ groups: [group, ...get().groups] });
    return group;
  },

  // ── 그룹 페이지 ──
  loadGroup: async (groupId) => {
    set({ currentGroupStatus: "loading", currentGroupError: null });
    try {
      const group = await api.fetchGroup(groupId);
      set({ currentGroup: group, currentGroupStatus: "loaded" });
      return group;
    } catch (e) {
      set({ currentGroupStatus: "error", currentGroupError: e });
      throw e;
    }
  },

  reissueInviteCode: async (groupId) => {
    const { inviteCode, inviteExpiresAt } =
      await api.reissueInviteCode(groupId);
    set((s) => ({
      currentGroup: { ...s.currentGroup, inviteCode, inviteExpiresAt },
    }));
  },

  leaveGroup: async (groupId, userId) => {
    const result = await api.leaveGroup(groupId, userId);
    set({
      currentGroup: null,
      groups: get().groups.filter((g) => g.id !== groupId),
    });
    return result; // { softDeleted, newOwnerId }
  },

  // ── 프로젝트 ──
  loadProjects: async (groupId) => {
    set({ projectsStatus: "loading" });
    const projects = await api.fetchProjects(groupId);
    set({ projects, projectsStatus: "loaded" });
  },

  createProject: async (groupId, input) => {
    const project = await api.createProject(groupId, input);
    set({ projects: [project, ...get().projects] });
    return project;
  },

  deleteProject: async (projectId) => {
    await api.deleteProject(projectId);
    set({ projects: get().projects.filter((p) => p.id !== projectId) });
  },

  clearCurrentGroup: () => set({ currentGroup: null, projects: [] }),
}));
