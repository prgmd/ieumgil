import { useCallback, useEffect, useState } from "react";
import * as api from "../api/groupPageApi";

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
    async (form) => {
      // 폼 값은 전부 문자열이다(number 타입 input 도 e.target.value 는 문자열,
      // 미입력 선택 항목은 빈 문자열). 서버는 Integer 를 기대하므로 여기서 타입을
      // 맞춘다 — 빈 문자열을 그대로 보내면 400 이 될 수 있다.
      const payload = {
        name: form.name.trim(),
        destination: form.destination.trim() || null,
        startDate: form.startDate,
        endDate: form.endDate,
        budgetHeadcount: Number(form.budgetHeadcount),
        targetBudget: form.targetBudget === "" ? null : Number(form.targetBudget),
        transportPref: form.transportPref,
      };

      // POST 응답은 { projectId } 하나뿐이므로(my-group-api.md) 카드에 필요한
      // 나머지 필드는 방금 보낸 값으로 채운다 — 목록 재조회를 아끼기 위함.
      // 전송값과 카드가 같은 payload 를 쓰도록 정규화를 이 층에 둔다.
      const created = await api.createProject(groupId, payload);
      const project = { status: "PLANNING", ...payload, ...created };
      setResult((prev) => ({ ...prev, projects: [project, ...prev.projects] }));
      return project;
    },
    [groupId],
  );

  const deleteProject = useCallback(async (projectId) => {
    await api.deleteProject(projectId);
    setResult((prev) => ({
      ...prev,
      projects: prev.projects.filter((p) => p.projectId !== projectId),
    }));
  }, []);

  return {
    projects: isValidId && !isStale ? result.projects : EMPTY,
    status: !isValidId ? "idle" : isStale ? "loading" : result.status,
    createProject,
    deleteProject,
  };
}
