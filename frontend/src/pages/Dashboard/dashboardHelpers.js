/**
 * 카테고리(대분류) 표. 색은 값을 직접 적지 않고 공통 토큰(tokens.css)을 가리킨다 —
 * 팔레트를 바꿀 일이 생기면 CSS 한 곳만 고치면 된다.
 * hex/bg 는 그대로 CSS 변수(--dc/--cb)나 배경색으로 넘어가므로 var() 문자열로 둔다.
 */
export const CAT_COLORS = {
  stay: { nm: "숙소", hex: "var(--stay, #8a5aa8)", bg: "var(--stayB, #f3edfa)" },
  food: { nm: "식당", hex: "var(--food, #d97e3c)", bg: "var(--foodB, #fdf1e4)" },
  spot: {
    nm: "명소/활동",
    hex: "var(--spot, #3e8e63)",
    bg: "var(--spotB, #eaf5ec)",
  },
  etc: { nm: "기타", hex: "var(--etc, #7a6a5c)", bg: "var(--etcB, #f1ece4)" },
  trans: {
    nm: "교통",
    hex: "var(--trans, #6b7fc7)",
    bg: "var(--transB, #eef0fb)",
  },
};

export const fmtTime = (mins) => {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
};

// 금액 표기 (QA 배치2) — 만원 이상은 "9.3만원"으로 축약해 긴 숫자를 줄이고,
// 0원/없음은 빈 문자열을 돌려준다(예전 "무료" 표기는 정보가 아니라 소음이었다).
// 호출부는 빈 값일 때 요소 자체를 그리지 않는다.
export const won = (n) => {
  if (!n) return "";
  if (n >= 10000) {
    const man = n / 10000;
    const text =
      man >= 100
        ? Math.round(man).toLocaleString("ko-KR")
        : man.toFixed(1).replace(/\.0$/, "");
    return `${text}만원`;
  }
  return `${n.toLocaleString("ko-KR")}원`;
};

// 소요 표기 (QA 배치2) — 60분 이상은 "1시간 15분"으로 읽기 좋게
export const fmtDur = (mins) => {
  if (mins == null) return "";
  if (mins < 60) return `${mins}분`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m > 0 ? `${h}시간 ${m}분` : `${h}시간`;
};

export const catOf = (item) => CAT_COLORS[item?.cat] || CAT_COLORS.etc;

/**
 * 요금이 "1인당"으로 오는 이동수단 — 표를 사람 수만큼 끊어야 한다.
 * 택시·자가용은 차 한 대 기준(요금을 나눠 내는 쪽)이라 곱하지 않고, 도보는 0원이다.
 */
const PER_PERSON_FARE_MODES = new Set([
  "TRANSIT",
  "TRAIN",
  "EXPRESS_BUS",
  "AIR",
]);

/** 이동수단 코드만으로 판정 — 블록이 아니라 후보(candidate)를 다룰 때 쓴다 */
export const isPerPersonMode = (mode) => PER_PERSON_FARE_MODES.has(mode);

export const isPerPersonFare = (item) =>
  item?.cat === "trans" && isPerPersonMode(item?.transportMeta?.chosen?.mode);

/**
 * 예산에 실제로 잡히는 금액.
 *
 * 블록의 cost 는 서버가 준 값 그대로다 — 대중교통·기차·항공은 1인 요금이라 그대로
 * 더하면 인원과 무관하게 한 명 몫만 잡힌다. 곱하기를 저장 시점이 아니라 합산 시점에
 * 하는 이유는, 나중에 여행 인원을 바꿔도 예산이 저절로 따라오게 하기 위함이다
 * (저장해 두면 인원 변경 때 모든 교통 블록을 다시 써야 한다).
 */
export const effectiveCostOf = (item, headcount) => {
  const cost = item?.cost || 0;
  return isPerPersonFare(item) ? cost * Math.max(1, headcount || 1) : cost;
};

// ── 블록 id 규약 ─────────────────────────────────────
// 서버에 아직 없는 블록을 구분하는 규약 — custom-(모달 저장 전), search-(생성 요청 중)
export const isTempId = (id) =>
  String(id).startsWith("custom-") || String(id).startsWith("search-");
// 서버에 실재하는 블록만 REST 를 태운다 — 임시 id·auto-(로컬 교통)는 제외
export const isServerBlock = (id) =>
  !isTempId(id) && !String(id).startsWith("auto-");

// 카카오 category_group_code → 화면 cat. 음식점(FD6)·카페(CE7)는 food,
// 숙박(AD5)은 stay, 그 외 장소는 spot 으로 본다.
export const catFromKakaoGroup = (code) => {
  if (code === "FD6" || code === "CE7") return "food";
  if (code === "AD5") return "stay";
  return "spot";
};

// "d3" → 3 (서버 dayNo)
export const dayNoOf = (dayKey) => Number(String(dayKey).replace("d", ""));

export const DAY_MS = 24 * 60 * 60 * 1000;
/** 프로젝트를 아직 못 불러왔을 때만 쓰는 Day 수 (기존 목업과 같은 4일). */
const FALLBACK_DAY_COUNT = 4;
const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

// 'YYYY-MM-DD' 를 그냥 new Date() 에 넣으면 UTC 자정으로 읽혀 KST에서 하루 밀린다.
export const parseDate = (iso) =>
  typeof iso === "string" && iso.length >= 10
    ? new Date(`${iso.slice(0, 10)}T00:00:00`)
    : null;

/**
 * 프로젝트 기간(startDate~endDate)에서 Day 키 목록을 만든다.
 * 그룹 페이지에서 기간을 수정하면 이 목록이 바뀌고, Day 탭·읽기 모드가 함께 따라간다.
 */
export function dayKeysOf(project) {
  const start = parseDate(project?.startDate);
  const end = parseDate(project?.endDate);
  const count =
    start && end
      ? Math.min(30, Math.max(1, Math.round((end - start) / DAY_MS) + 1))
      : FALLBACK_DAY_COUNT;
  return Array.from({ length: count }, (_, i) => `d${i + 1}`);
}

/** Day n(0-based)의 실제 날짜. 기간을 모르면 빈 문자열 — 가짜 날짜를 만들지 않는다. */
export function dayDate(project, index, style = "full") {
  const start = parseDate(project?.startDate);
  if (!start) return "";
  const d = new Date(start.getTime() + index * DAY_MS);
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  if (style === "short") return `${mm}.${dd}`;
  return `${d.getFullYear()}.${mm}.${dd} (${WEEKDAYS[d.getDay()]})`;
}

/** 없는/잘못된 cat 은 기타로 모은다 — 카테고리 합계에서 한 칸이 사라지지 않게. */
export const catKeyOf = (item) => (CAT_COLORS[item?.cat] ? item.cat : "etc");
