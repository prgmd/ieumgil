// features/place/api/placeApi.js
//
// 장소 검색·지오코딩 REST 계층. 명세: docs/api/dashboard-api.md
//
// 카카오를 브라우저에서 직접 부르지 않는다 — 검색·지오코딩은 전부 서버를 거친다.
// 지도 렌더링(마커·InfoWindow)만 Kakao Maps SDK 를 계속 쓴다.
//
// 대시보드와 그룹(프로젝트 생성)이 함께 쓰므로 features/place 에 둔다.

import axiosInstance from "../../../global/api/axiosInstance";

// CustomResponse 는 @JsonInclude(NON_NULL) 이라 result 가 null 이면 키 자체가 사라진다.
// 그래서 `?? data`(봉투 폴백)를 쓰면 "결과 없음"이 truthy 한 봉투 객체로 새어 나온다 —
// 각 함수가 자기 빈 값(빈 배열 / null)으로 직접 떨어뜨린다.
function unwrap(data) {
  return data?.result;
}

/** 화면의 에러 분기({ code })를 위해 백엔드 응답 본문을 그대로 던진다 (dashboardApi 와 동일) */
function unwrapError(error) {
  throw error.response?.data ?? error;
}

/**
 * 키워드 장소 검색. 좌표를 주면 그 지점 기준 거리순으로 정렬된다(범위 제한은 아니다).
 * @returns {Promise<Array<{placeId,name,address,lat,lng,category,categoryCode,phone}>>}
 *          결과가 없으면 빈 배열 — 에러가 아니다
 */
export async function searchPlaces(query, { lat, lng } = {}) {
  try {
    const { data } = await axiosInstance.get("/places", {
      params: { query, ...(lat != null && lng != null ? { lat, lng } : {}) },
    });
    return unwrap(data) ?? [];
  } catch (error) {
    unwrapError(error);
  }
}

/**
 * 주소 → 좌표. 찾지 못하면 서버가 404(PLACE404)를 준다.
 * @returns {Promise<{lat:number, lng:number, roadAddress:string, jibunAddress:string}>}
 */
export async function geocodeAddress(address) {
  try {
    const { data } = await axiosInstance.get("/places/geocode", { params: { address } });
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/**
 * 좌표 → 주소. 지도 핀을 찍었을 때 주소를 역채우는 데 쓴다.
 * @returns {Promise<{address:string, roadAddress:string}|null>} 못 찾으면 null
 */
export async function reverseGeocode(lat, lng) {
  try {
    const { data } = await axiosInstance.get("/places/address", { params: { lat, lng } });
    return unwrap(data) ?? null;
  } catch (error) {
    unwrapError(error);
  }
}
