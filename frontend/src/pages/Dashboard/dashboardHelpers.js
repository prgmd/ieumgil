import {
  MINUTES_PER_DAY,
  dayNoOfOffset,
} from "../../features/dashboard/api/dashboardApi";

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

/**
 * 시간축 값(Day 1 00:00 기준 절대 분)을 하루 안의 "HH:mm" 으로 찍는다.
 * 나머지를 취하지 않으면 Day 2 의 00:30(=1470분)이 "24:30" 으로, Day 3 은
 * "48:30" 으로 보인다 — 화면이 읽는 시각은 언제나 그 Day 안의 시각이다.
 * Day 번호가 함께 필요한 자리는 dayNoOfOffset 으로 따로 얻는다.
 */
export const fmtTime = (mins) => {
  const minuteOfDay = mins % MINUTES_PER_DAY;
  const h = Math.floor(minuteOfDay / 60);
  const m = minuteOfDay % 60;
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

/**
 * 이 Day 의 00:00(dayBase, 절대 분)을 가로질러 이어지는 블록들.
 *
 * 자정을 넘긴 블록은 한 행이고 시작한 Day 의 것이다 — 그래서 다음 Day 쪽에서는
 * 보드 전체(items)를 훑어야만 찾을 수 있다.
 * excludeId 는 지금 옮기는 중인 블록 — 제 꼬리에 제가 막히면 안 된다.
 *
 * 돌려주는 항목은 화면에만 쓰는 파생값이다: 체인에 넣지 않고, 블록 수·예산·교통
 * 짝짓기 어디에도 세지 않는다.
 */
export const spilloversInto = (itemsMap, dayBase, excludeId = null) =>
  Object.entries(itemsMap ?? {})
    .filter(([id, it]) => {
      if (excludeId != null && id === String(excludeId)) return false;
      if (it?.startMins == null) return false; // 후보(POOL)는 시간축 위에 없다
      return it.startMins < dayBase && it.startMins + it.dur > dayBase;
    })
    .map(([id, it]) => ({ id, item: it, endMins: it.startMins + it.dur }));

/**
 * 이 Day 에서 블록이 시작할 수 있는 가장 이른 시각.
 * 자정을 넘어온 블록이 없으면 그 Day 의 00:00(dayBase)이고, 있으면 그중 가장
 * 늦게 끝나는 블록의 끝이다 — 화면의 띠(.tl-spill)가 덮고 있는 구간 그대로다.
 * 겹침 해소와 드롭 클램프가 같은 값을 봐야 "띠 위에 놓았는데 왜 내려가지" 가
 * 없다.
 */
export const spilloverFloorOf = (itemsMap, dayBase, excludeId = null) =>
  spilloversInto(itemsMap, dayBase, excludeId).reduce(
    (floor, spill) => Math.max(floor, spill.endMins),
    dayBase,
  );

/**
 * 보드 위 블록 id 를 오프셋 순으로 세운 하나의 목록.
 *
 * 소속의 근거는 오프셋 하나다 — startMins 가 있으면 시간축 위에 있고, 없으면
 * 후보(POOL)다. Day 별 체인 맵을 따로 두지 않는 이유가 여기 있다: 맵을 두면
 * "오프셋이 가리키는 Day"와 "실제로 들어 있는 체인"이 갈라질 수 있고, 자정을
 * 넘겨 밀린 블록마다 그 둘을 다시 맞춰야 했다.
 *
 * 순서는 서버의 보드 정렬(BlockRepository.findChain: startOffsetMinutes,
 * order_key, id)과 같다 — orderKey 는 같은 시각에 놓인 블록들의 동점 처리다.
 * 키가 없는 로컬 전용 블록(auto- 등)은 동점 비교를 건너뛰고 id 로 끊는다.
 */
export const boardOf = (itemsMap) =>
  Object.values(itemsMap ?? {})
    .filter((b) => b?.startMins != null)
    .sort((a, b) => {
      if (a.startMins !== b.startMins) return a.startMins - b.startMins;
      if (a.orderKey != null && b.orderKey != null && a.orderKey !== b.orderKey)
        return a.orderKey < b.orderKey ? -1 : 1;
      if (a.id === b.id) return 0;
      return String(a.id) < String(b.id) ? -1 : 1;
    })
    .map((b) => b.id);

/**
 * 그 Day 에 앉은 블록들 — 보드 목록의 부분집합이라 오프셋 순서를 그대로 물려받는다.
 * Day 는 저장하는 값이 아니라 시작 오프셋에서 유도한다(서버 Block.dayNo() 와 같은
 * 규칙). 자정을 넘긴 블록은 시작한 Day 의 것이다.
 *
 * 여행 기간 밖(마지막 Day 의 자정 너머)으로 밀린 블록은 어느 Day 에도 안 잡힌다 —
 * 그런 Day 가 없기 때문이다. 보드에는 그대로 남아 그려지고 저장된다.
 */
export const blocksOfDay = (board, itemsMap, dayKey) => {
  const dayNo = dayNoOf(dayKey);
  return (board ?? []).filter(
    (id) => dayNoOfOffset(itemsMap?.[id]?.startMins) === dayNo,
  );
};
