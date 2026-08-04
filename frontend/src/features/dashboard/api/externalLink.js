// features/dashboard/api/externalLink.js
//
// 블록 외부 링크(카카오맵 장소 / 축제 공식 홈페이지) 이동.
// 설계: docs/superpowers/specs/2026-08-04-block-external-link-design.md

import { fetchFestivalHomepage } from "./dashboardApi";

/** 카카오 장소 상세 딥링크. source=KAKAO 블록의 placeId로 만든다. */
export function buildKakaoPlaceUrl(placeId) {
  return `https://place.map.kakao.com/${placeId}`;
}

/** 카카오맵 검색. 축제 홈페이지가 없을 때 축제명으로 폴백한다. */
export function buildKakaoSearchUrl(name) {
  return `https://map.kakao.com/?q=${encodeURIComponent(name ?? "")}`;
}

function openUrl(url) {
  window.open(url, "_blank", "noreferrer,noopener");
}

/**
 * 블록의 출처에 맞는 외부 링크를 새 탭으로 연다.
 * - KAKAO: place.map.kakao.com/{placeId} 즉시
 * - BOT(축제): 저장된 홈페이지 조회 → 있으면 그 URL, 없으면 카카오 검색 폴백
 */
export async function openBlockLink(block) {
  if (block.source === "KAKAO" && block.placeId) {
    openUrl(buildKakaoPlaceUrl(block.placeId));
    return;
  }
  if (block.source === "BOT" && block.placeId) {
    const res = await fetchFestivalHomepage(block.placeId);
    openUrl(res?.url ? res.url : buildKakaoSearchUrl(block.name));
  }
}

/** 이 블록에 외부 링크 아이콘을 띄울지. */
export function hasExternalLink(block) {
  return Boolean(block?.placeId) && (block.source === "KAKAO" || block.source === "BOT");
}
