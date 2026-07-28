import { useCallback, useEffect, useState } from "react";
import * as api from "../../my/api/groupApi";

const EMPTY = [];

/**
 * 그룹에 속한 프로젝트 목록을 소유하는 훅.
 * useGroupDetail 과 같은 규칙 — 결과에 groupId 를 함께 담아두고 렌더 시점에
 * 현재 groupId 와 비교해, 이전 그룹의 프로젝트가 남아 보이지 않게 한다.
 *
 * @param {number} groupId Number(useParams().groupId) — 잘못된 URL 이면 NaN 일 수 있다.
 */
export function useProjects(groupId) {
  const isValidId = Number.isInteger(groupId);

  const [result, setResult] = useState({
    groupId: null,
    projects: EMPTY,
    status: "idle", // idle | loaded | error
  });

  useEffect(() => {
    // 잘못된 URL 의 오류 안내·리다이렉트는 useGroupDetail 쪽에서 하므로 조용히 멈춘다.
    if (!isValidId) return;

    let cancelled = false;

    api
      .fetchProjects(groupId)
      .then((list) => {
        if (cancelled) return;
        setResult({ groupId, projects: list ?? EMPTY, status: "loaded" });
      })
      .catch(() => {
        if (cancelled) return;
        setResult({ groupId, projects: EMPTY, status: "error" });
      });

    return () => {
      cancelled = true;
    };
  }, [groupId, isValidId]);

  const isStale = result.groupId !== groupId;

  // 갱신은 항상 함수형으로 — groupId 가 바뀐 뒤 늦게 끝난 mutation 이
  // 새 그룹의 목록에 섞이지 않도록 이전 레코드 위에만 반영된다.
  const createProject = useCallback(
    async (input) => {
      const project = await api.createProject(groupId, input);
      setResult((prev) => ({ ...prev, projects: [project, ...prev.projects] }));
      return project;
    },
    [groupId],
  );

  const deleteProject = useCallback(async (projectId) => {
    await api.deleteProject(projectId);
    setResult((prev) => ({
      ...prev,
      projects: prev.projects.filter((p) => p.id !== projectId),
    }));
  }, []);

  return {
    projects: isValidId && !isStale ? result.projects : EMPTY,
    status: !isValidId ? "idle" : isStale ? "loading" : result.status,
    createProject,
    deleteProject,
  };
}
