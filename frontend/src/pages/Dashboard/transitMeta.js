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
 * segment 는 세그먼트 레벨 메타(intercity/timetableApplied/timetableSkipReason)만 뽑는다.
 */
export const buildTransportMeta = (segment, chosenCandidate, chosenDeparture) => ({
  generated: true,
  segment: segment
    ? {
        intercity: segment.intercity,
        timetableApplied: segment.timetableApplied,
        timetableSkipReason: segment.timetableSkipReason ?? null,
      }
    : null,
  chosen: chosenCandidate
    ? { ...chosenCandidate, departureName: chosenDeparture?.name ?? null }
    : null,
  candidates: segment?.candidates ?? [],
});
