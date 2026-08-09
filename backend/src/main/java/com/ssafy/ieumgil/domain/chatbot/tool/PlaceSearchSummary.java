package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;

import java.util.List;

public record PlaceSearchSummary(String name, String address, String category, String url, String reason) {

    private static final String KAKAO_PLACE_URL_FORMAT = "https://place.map.kakao.com/%s";

    public static PlaceSearchSummary from(PlaceResDTO.Place place) {
        return from(place, List.of());
    }

    /**
     * @param reasons 서버가 계산한 추천 근거. 비어 있으면 {@code reason}은 null 이다 —
     *                빈 문자열을 넣으면 모델이 "이유 없음"을 이유로 착각할 수 있다.
     */
    public static PlaceSearchSummary from(PlaceResDTO.Place place, List<String> reasons) {
        return new PlaceSearchSummary(
                place.name(),
                place.address(),
                place.category(),
                KAKAO_PLACE_URL_FORMAT.formatted(place.placeId()),
                reasons.isEmpty() ? null : String.join(" · ", reasons)
        );
    }
}
