// pages/Dashboard/hooks/useBudget.js
//
// 목표 예산과 지출 집계. 총액은 보드에 배치된 블록에서 파생하고(후보 블록은 세지
// 않는다), 1인 요금 수단은 인원만큼 곱한다(effectiveCostOf).
//
// setTargetBudget 을 밖으로 노출하는 이유: 원격 op TARGET_BUDGET_CHANGED 가
// 다른 멤버의 변경을 이 상태에 반영해야 하는데, op 핸들러는 부모가 소유한다.
// 목표 예산 변경은 타이머로 디바운스하고, 그 사이 낡은 targetBudget 대신
// targetBudgetRef 로 최신값을 읽어 누적한다 — 두 ref 는 이 훅 안에서만 산다.

import { useState, useRef, useEffect, useMemo } from "react";
import { CAT_COLORS, catKeyOf, effectiveCostOf } from "../dashboardHelpers";
import * as blockApi from "../../../features/dashboard/api/dashboardApi";

export function useBudget({
  projectId,
  project,
  board,
  items,
  rollbackToServer,
}) {
  // 정산·1인 요금 환산의 기준 인원. 프로젝트에 값이 없으면 최소 1명으로 본다.
  const headcount = Math.max(1, project?.budgetHeadcount || 1);

  // 예산은 보드에 배치된 블록만 센다(명세) — 후보(POOL)는 아직 계획이 아니라
  // 검토 중인 카드라서, 합산에 넣으면 "쓸지 말지 모르는 돈"이 예산을 잠식한다.
  // board 가 이미 "배치된 블록 전부"라 Day 별로 모을 것이 없다.
  const totalBudget = board.reduce(
    (sum, id) => sum + effectiveCostOf(items[id], headcount),
    0,
  );
  // 총액을 인원으로 나눈 값 — 대중교통처럼 1인 요금인 항목은 이미 곱해 넣었으므로
  // 여기서 나누면 다시 1인 몫으로 돌아온다.
  const perPersonBudget = Math.round(totalBudget / headcount);
  const [targetBudget, setTargetBudget] = useState(0);

  // 목표 예산 저장은 디바운스한다 — ± 버튼 연타(십만원 단위)를 요청 1건으로 모은다.
  // 타이머가 언마운트 후에 발화해도 요청은 그대로 나간다(마지막 조작 유실 방지).
  // ± 버튼과 직접 입력이 같은 경로(commitTargetBudget)를 탄다.
  const targetBudgetTimerRef = useRef(null);
  const commitTargetBudget = (value) => {
    const next = Math.max(0, value); // 0원 밑으로는 안 내려가게 방지
    setTargetBudget(next);

    clearTimeout(targetBudgetTimerRef.current);
    targetBudgetTimerRef.current = setTimeout(() => {
      blockApi.updateTargetBudget(projectId, next).catch(rollbackToServer);
    }, 600);
  };
  // 홀드 반복(100ms 간격 연속 호출)은 렌더 사이에 여러 번 발화한다 — 클로저의
  // targetBudget 은 그 사이 낡아 있으므로 최신값은 ref 로 읽어 누적시킨다
  const targetBudgetRef = useRef(targetBudget);
  useEffect(() => {
    targetBudgetRef.current = targetBudget;
  });
  const bumpTargetBudget = (amount) =>
    commitTargetBudget(targetBudgetRef.current + amount);

  // 직접 입력 편집 상태 — null 이면 표시 모드, 문자열이면 입력 모드(입력 중 원문 유지)
  const [budgetDraft, setBudgetDraft] = useState(null);
  const budgetEditCancelledRef = useRef(false); // Esc 취소가 blur 커밋으로 이어지지 않게
  const commitBudgetDraft = () => {
    if (budgetEditCancelledRef.current) {
      budgetEditCancelledRef.current = false;
      setBudgetDraft(null);
      return;
    }
    const parsed = Number(budgetDraft);
    if (budgetDraft !== null && budgetDraft !== "" && Number.isFinite(parsed)) {
      commitTargetBudget(Math.floor(parsed));
    }
    setBudgetDraft(null);
  };
  const budgetPct =
    targetBudget > 0 ? Math.min(100, (totalBudget / targetBudget) * 100) : 0;
  const remainingBudget = targetBudget - totalBudget;

  /**
   * 카테고리(대분류)별 예산 세그먼트.
   *
   * 사용량 전체가 갈색 한 덩어리였을 때는 "무엇이 예산을 잡아먹는지"를 알 수 없었다.
   * 블록 하나하나로 쪼개면 칸이 너무 잘게 나뉘므로, 숙소·식당·명소/활동·기타·교통
   * 다섯 대분류로 합산해 카테고리 색 그대로 쌓는다.
   *
   * 칸의 폭은 "희망 예산 대비 비율"이다 — 그래야 남은 예산(빈 트랙)과 같은 자를 쓴다.
   * 예산을 넘긴 경우에는 기준을 총 사용액으로 바꿔 막대를 꽉 채우고, 초과분은 아래
   * 텍스트가 알려준다(비율이 100%를 넘는 칸은 그릴 수 없으므로).
   *
   * 순서는 금액순이 아니라 CAT_COLORS 선언 순서다 — 블록을 하나 고칠 때마다 칸이
   * 자리를 바꾸면 눈으로 따라가기 어렵다.
   */
  const budgetSegments = useMemo(() => {
    const denominator =
      remainingBudget < 0 || targetBudget <= 0
        ? totalBudget || 1
        : targetBudget;

    // 총액(totalBudget)과 같은 기준 — 보드에 배치된 블록만 (후보는 계획이 아니다).
    // 1인 요금 곱하기도 같은 함수를 써야 칸의 합이 총액과 맞는다.
    const sumByCat = {};
    board.forEach((id) => {
      const item = items[id];
      const cost = effectiveCostOf(item, headcount);
      if (cost <= 0) return;
      const cat = catKeyOf(item);
      sumByCat[cat] = (sumByCat[cat] ?? 0) + cost;
    });

    return Object.keys(CAT_COLORS)
      .filter((cat) => sumByCat[cat] > 0)
      .map((cat) => ({
        cat,
        name: CAT_COLORS[cat].nm,
        color: CAT_COLORS[cat].hex,
        cost: sumByCat[cat],
        percent: (sumByCat[cat] / denominator) * 100,
        shareOfTotal:
          totalBudget > 0 ? (sumByCat[cat] / totalBudget) * 100 : 0,
      }));
  }, [items, board, headcount, targetBudget, totalBudget, remainingBudget]);

  return {
    headcount,
    totalBudget,
    perPersonBudget,
    targetBudget,
    setTargetBudget,
    budgetDraft,
    setBudgetDraft,
    commitBudgetDraft,
    // Esc 취소 표시 — 입력 필드가 부모(BudgetPanel)에 있으므로 같이 내보낸다.
    // commitBudgetDraft 가 blur 에서 이 값을 보고 커밋을 건너뛴다.
    budgetEditCancelledRef,
    bumpTargetBudget,
    budgetPct,
    remainingBudget,
    budgetSegments,
  };
}
