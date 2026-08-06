package com.ssafy.ieumgil.domain.place.service;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;

import java.util.List;
import java.util.Optional;

public interface PlaceQueryService {

    /**
     * 챗봇 장소검색 상한. 결과가 LLM 프롬프트로 들어가므로 사용자 검색(15)과 같이
     * 올리면 안 된다 — 토큰이 3배가 된다. 일반 모드 tool과 지도뷰포트 tool이 공유하는
     * 단일 값이라 여기 인터페이스에 둔다.
     */
    int CHATBOT_SEARCH_LIMIT = 5;

    List<PlaceResDTO.Place> searchPlaces(String query, Double lat, Double lng);

    /** 지도 뷰포트 범위 안에서만 검색한다 (챗봇 지도 기반 추천 모드) */
    List<PlaceResDTO.Place> searchPlacesInRect(String query, double swLat, double swLng, double neLat, double neLng);

    Optional<PlaceResDTO.Address> reverseGeocode(double lat, double lng);

    Optional<PlaceResDTO.WalkingRoute> getWalkingRoute(double startLat, double startLng, double endLat, double endLng);

    Optional<PlaceResDTO.TaxiRoute> getTaxiRoute(double startLat, double startLng, double endLat, double endLng);
}
