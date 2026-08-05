// pages/Dashboard/mapPins.js
//
// 지도 핀 이미지. 기본 마커를 그대로 쓰면 "이미 일정에 넣은 곳"과 "방금 검색한
// 후보"가 똑같이 보여 구분이 안 된다. 모양은 같게 두고 색 두 가지로만 가른다.
//
//   초록 : 타임라인(계획표)에 들어가 있는 블록
//   파랑 : 검색 결과 — 아직 후보
//
// 카테고리별로 나누지 않는 이유는, 지도에서 알고 싶은 건 "이 장소가 숙소냐 식당이냐"
// 보다 "이미 넣었냐 아직 후보냐"이기 때문이다. 카테고리 색은 보드 카드가 이미 쥐고 있다.

// 블록 카드 색과 겹치지 않게 고른 값들.
// 보드의 카테고리 색은 전부 톤 낮은 파스텔 계열이다 —
//   숙소 #8a5aa8 · 식당 #d97e3c · 명소 #3e8e63 · 기타 #7a6a5c · 교통 #6b7fc7
// 핀은 그보다 짙고 채도 높은 쪽으로 빼서, 같은 초록·파랑 계열이라도 한눈에 갈리게 한다.
// (색을 새로 고를 일이 생기면 위 다섯 값과의 거리를 먼저 확인할 것.)
const PLAN_COLOR = "#0f7a6c"; // 짙은 청록 — 명소 초록(#3e8e63)보다 어둡고 푸르다
const SEARCH_COLOR = "#1668dc"; // 선명한 파랑 — 교통 남보라(#6b7fc7)보다 훨씬 진하다
const PAPER = "#fffdf8"; // 보드 배경색 — 테두리로 쓰면 지도 위에서 또렷하다

// 그림을 그리는 좌표계는 그대로 두고(VIEW_*) 화면에 찍히는 크기만 정한다 —
// viewBox 가 알아서 맞춰 주므로 path 좌표를 다시 계산할 필요가 없다.
const VIEW_W = 30;
const VIEW_H = 40;

// 순번이 들어가는 계획 핀은 숫자가 읽혀야 해서 조금 크다. 검색 핀은 숫자가 없어
// 작게 둔다 — 결과가 여럿 떠도 짜 둔 동선을 덜 가린다.
const PLAN_SIZE = { w: 24, h: 32 };
const SEARCH_SIZE = { w: 15, h: 20 };

/**
 * 물방울 핀 하나. 색·크기·가운데 숫자만 갈아 끼운다.
 * data URI 로 넘겨서 파일을 따로 두지 않고 런타임에 값을 정한다.
 * offset 은 "좌표에 닿는 지점" — 물방울의 뾰족한 끝(아래 중앙)이다.
 *
 * @param {string} color
 * @param {{w:number,h:number}} size 화면에 찍히는 크기(px)
 * @param {number|null} label 가운데 숫자. null 이면 작은 점만 찍는다.
 */
function pinImage(color, size, label = null) {
  const { Size, Point, MarkerImage } = window.kakao.maps;

  // 숫자는 흰 원 위에 핀 색으로 쓴다 — 색 위에 흰 글씨보다 작은 크기에서 잘 읽힌다.
  // 두 자리는 원을 넘치지 않게 글자를 줄인다.
  const head =
    label == null
      ? `<circle cx="15" cy="14" r="4.5" fill="${PAPER}"/>`
      : `<circle cx="15" cy="14" r="9" fill="${PAPER}"/>
  <text x="15" y="18.6" text-anchor="middle"
        font-family="system-ui, -apple-system, sans-serif"
        font-size="${String(label).length > 1 ? 11 : 13.5}"
        font-weight="700" fill="${color}">${label}</text>`;

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size.w}" height="${size.h}" viewBox="0 0 ${VIEW_W} ${VIEW_H}">
  <path d="M15 39C15 39 28 24 28 14A13 13 0 1 0 2 14C2 24 15 39 15 39Z"
        fill="${color}" stroke="${PAPER}" stroke-width="2.5" stroke-linejoin="round"/>
  ${head}
</svg>`;

  return new MarkerImage(
    `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`,
    new Size(size.w, size.h),
    { offset: new Point(size.w / 2, size.h) },
  );
}

/**
 * 타임라인에 들어가 있는 블록 핀 — 초록 + 방문 순번.
 * 순번이 있어야 지도만 보고도 그날 동선의 순서를 읽을 수 있다.
 * @param {number} order 그날 좌표 있는 블록들 중 몇 번째인지 (1-based)
 */
export function planPinImage(order) {
  return pinImage(PLAN_COLOR, PLAN_SIZE, order);
}

/** 검색 결과 핀 — 파랑. 아직 후보라 순번을 매기지 않는다. */
export function searchPinImage() {
  return pinImage(SEARCH_COLOR, SEARCH_SIZE);
}

/** 이동 경로 선 색 — 계획 핀과 같은 초록. 검색 파랑과 섞이지 않는다 */
export const ROUTE_LINE_COLOR = PLAN_COLOR;
