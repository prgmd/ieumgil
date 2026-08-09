package com.ssafy.ieumgil.domain.place.service;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;

import java.util.List;
import java.util.Optional;

public interface PlaceQueryService {

    /**
     * 일반 모드 챗봇 장소검색 상한. 결과가 LLM 프롬프트로 들어가므로 사용자 검색(15)과 같이
     * 올리면 안 된다 — 토큰이 3배가 된다. 지도 뷰포트 경로는 재정렬 여지를 만들려고 더 많이
     * 받으므로 이 값을 쓰지 않는다({@code MAP_SEARCH_LIMIT}/{@code LLM_CANDIDATE_LIMIT}).
     */
    int CHATBOT_SEARCH_LIMIT = 5;

    /**
     * 지도 뷰포트 검색이 카카오에서 받는 건수.
     *
     * <p>{@code CHATBOT_SEARCH_LIMIT}(5)보다 많이 받는 이유는 재정렬 여지를 만들기 위해서다 —
     * 5건만 받으면 순서를 바꿔도 같은 5건이라 의미가 없다. LLM에 넘기는 건 재정렬 뒤
     * {@code LLM_CANDIDATE_LIMIT}건뿐이므로 프롬프트 토큰은 늘지 않는다.
     */
    int MAP_SEARCH_LIMIT = 15;

    /** 재정렬 후 LLM 프롬프트에 넣는 상한 */
    int LLM_CANDIDATE_LIMIT = 8;

    List<PlaceResDTO.Place> searchPlaces(String query, Double lat, Double lng);

    /** 지도 뷰포트 범위 안에서만 검색한다 (챗봇 지도 기반 추천 모드) */
    List<PlaceResDTO.Place> searchPlacesInRect(String query, double swLat, double swLng, double neLat, double neLng);

    Optional<PlaceResDTO.Address> reverseGeocode(double lat, double lng);

    Optional<PlaceResDTO.Geocode> geocodeAddress(String address);

    Optional<PlaceResDTO.WalkingRoute> getWalkingRoute(double startLat, double startLng, double endLat, double endLng);

    Optional<PlaceResDTO.TaxiRoute> getTaxiRoute(double startLat, double startLng, double endLat, double endLng);
}
