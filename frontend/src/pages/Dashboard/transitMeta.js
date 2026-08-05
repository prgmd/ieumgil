// pages/Dashboard/transitMeta.js
//
// index.jsx(교통 블록 생성·편집 로직)와 components/TransitCandidateCard.jsx(카드 렌더)가
// 공유하는 순수 데이터/헬퍼만 모아 둔다. index.jsx 에서 export 하면 그 파일의 유일한
// 컴포넌트 export(DashboardPage) 원칙이 깨져 Vite Fast Refresh 가 index.jsx 전체를
// 완전 새로고침으로 처리하게 된다 — 그래서 별도 모듈로 뺀다.

// 이동수단 코드 → 표시용 아이콘·이름 (label 이 비어 올 때의 폴백)
export const TRANSIT_MODE_META = {
  TRANSIT: { ico: "🚌", nm: "대중교통" },
  TRAIN: { ico: "🚄", nm: "기차" },
  EXPRESS_BUS: { ico: "🚍", nm: "고속·시외버스" },
  AIR: { ico: "✈️", nm: "항공" },
  TAXI: { ico: "🚕", nm: "택시" },
  CAR: { ico: "🚗", nm: "자가용" },
  WALK: { ico: "🚶", nm: "도보" },
};

/**
 * v2 Candidate + 그 구간 candidates 스냅샷 → transportMeta.
 * chosen 은 선택된 후보 원본에 departureName(시외 편, 시내면 null)만 얹는다.
 * segment 는 세그먼트 레벨 메타(intercity/timetableApplied/timetableSkipReason/referenceAt)만 뽑는다.
 */
export const buildTransportMeta = (segment, chosenCandidate, chosenDeparture) => ({
  generated: true,
  segment: segment
    ? {
        intercity: segment.intercity,
        timetableApplied: segment.timetableApplied,
        timetableSkipReason: segment.timetableSkipReason ?? null,
        referenceAt: segment.referenceAt ?? null,
      }
    : null,
  chosen: chosenCandidate
    ? { ...chosenCandidate, departureName: chosenDeparture?.name ?? null }
    : null,
  candidates: segment?.candidates ?? [],
});

/** 구간 하나(leg)를 한 조각 문자열로. 도보는 노선명이 없다. */
const legText = (leg) => {
  if (leg?.type === "WALK") return `도보 ${leg.durationMin}분`;
  const line = leg?.lineName ? `${leg.lineName} ` : "";
  return `${line}${leg?.from ?? "?"}→${leg?.to ?? "?"}`;
};

/**
 * 교통 블록 카드에 한 줄로 띄울 경로 요약.
 *
 * 상세 모달을 열어야만 "어느 버스를 타고 어디서 갈아타는지" 보이던 정보를,
 * 타임라인 카드에서 바로 읽히게 하려고 만든다. 카드 한 줄이라 전부 담을 수는
 * 없으니 노선·환승 골자만 남기고 나머지(요금 옵션·출발편 목록 등)는 모달에 둔다.
 *
 * @param {object} item 화면 블록
 * @returns {{ text: string, transfers: number|null } | null}
 *          경로 정보가 없으면 null — 호출부가 기존 주소 표시로 되돌린다.
 */
export const transitRouteSummary = (item) => {
  const chosen = item?.transportMeta?.chosen;
  if (!chosen) return null;

  // 시내 대중교통 — 승·하차 구간(legs)이 곧 경로다
  if (chosen.legs?.length > 0) {
    return {
      text: chosen.legs.map(legText).join(" · "),
      transfers: chosen.transferCount ?? null,
    };
  }

  // 시외(기차·고속버스·항공) — 고른 편 하나가 경로 전부다
  const departure = (chosen.departures ?? []).find(
    (d) => d.name === chosen.departureName,
  );
  if (departure) {
    const time = departure.departureAt
      ? ` ${departure.departureAt}${departure.arrivalAt ? `→${departure.arrivalAt}` : ""}`
      : "";
    return { text: `${departure.name}${time}`, transfers: null };
  }

  // 좌표만 있고 경로 상세가 없는 수단(택시·자가용·도보)
  return chosen.label ? { text: chosen.label, transfers: null } : null;
};
