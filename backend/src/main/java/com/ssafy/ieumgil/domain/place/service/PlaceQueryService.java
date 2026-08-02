package com.ssafy.ieumgil.domain.place.service;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;

import java.util.List;
import java.util.Optional;

public interface PlaceQueryService {

    List<PlaceResDTO.Place> searchPlaces(String query, Double lat, Double lng);

    /** 지도 뷰포트 범위 안에서만 검색한다 (챗봇 지도 기반 추천 모드) */
    List<PlaceResDTO.Place> searchPlacesInRect(String query, double swLat, double swLng, double neLat, double neLng);

    Optional<PlaceResDTO.Address> reverseGeocode(double lat, double lng);

    Optional<PlaceResDTO.WalkingRoute> getWalkingRoute(double startLat, double startLng, double endLat, double endLng);

    Optional<PlaceResDTO.TaxiRoute> getTaxiRoute(double startLat, double startLng, double endLat, double endLng);
}
