package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;

import java.util.Optional;

public class KakaoPlaceCoordinateResolver {

    private final PlaceQueryService placeQueryService;

    public KakaoPlaceCoordinateResolver(PlaceQueryService placeQueryService) {
        this.placeQueryService = placeQueryService;
    }

    /** 목적지+장소명으로 검색해 첫 번째 결과를 좌표 후보로 삼는다. 결과 없으면 빈 Optional. */
    public Optional<PlaceResDTO.Place> resolve(String destination, String placeName) {
        String query = destination + " " + placeName;
        return placeQueryService.searchPlaces(query, null, null).stream().findFirst();
    }
}
