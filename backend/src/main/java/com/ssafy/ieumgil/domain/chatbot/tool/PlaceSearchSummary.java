package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;

public record PlaceSearchSummary(String name, String address, String category, String url) {

    private static final String KAKAO_PLACE_URL_FORMAT = "https://place.map.kakao.com/%s";

    public static PlaceSearchSummary from(PlaceResDTO.Place place) {
        return new PlaceSearchSummary(
                place.name(),
                place.address(),
                place.category(),
                KAKAO_PLACE_URL_FORMAT.formatted(place.placeId())
        );
    }
}
