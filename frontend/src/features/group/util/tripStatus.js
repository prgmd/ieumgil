/**
 * 프로젝트(여행)가 끝났는지 판정한다.
 *
 * 서버의 PROJECT.status(PLANNING↔DONE)는 아직 아무도 바꾸지 않아 전부 PLANNING 으로
 * 남아 있다 — 그래서 화면에서는 종료일이 지났는지로 판정한다. 서버가 DONE 을 쓰기
 * 시작하면 그 값도 함께 존중한다(둘 중 하나라도 완료면 완료).
 */

/** 오늘을 "YYYY-MM-DD" 로 — 로컬 시간대 기준 */
const todayISO = () => {
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${now.getFullYear()}-${month}-${day}`;
};

/**
 * 종료일이 오늘보다 이전이면 완료 — 종료일 당일은 아직 여행 중이다.
 *
 * ISO 날짜 문자열은 사전순 비교가 곧 시간순 비교라 그대로 비교한다.
 * new Date("2026-08-04") 로 바꾸면 UTC 자정으로 파싱돼 KST 에서 하루 밀린다 —
 * 날짜만 다루는 값에 Date 를 끼우면 시간대 버그가 따라온다.
 *
 * @param {{status?: string, endDate?: string|null}} project
 */
export function isTripFinished(project) {
  if (!project) return false;
  if (project.status === "DONE") return true;
  if (!project.endDate) return false; // 기간 미정 — 끝났다고 볼 근거가 없다
  return project.endDate < todayISO();
}
