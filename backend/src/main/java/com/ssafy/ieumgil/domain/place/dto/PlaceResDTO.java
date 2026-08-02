package com.ssafy.ieumgil.domain.place.dto;

import lombok.Builder;

public class PlaceResDTO {

    @Builder
    public record Place(
            String placeId,
            String name,
            String address,
            Double lat,
            Double lng,
            String category
    ) {
    }

    public record Address(String address, String roadAddress) {
    }

    /** 소요시간은 <b>분</b>이다 — 카카오 응답의 초를 {@code PlaceQueryServiceImpl}에서 변환해 담는다. */
    public record WalkingRoute(int distance, int durationMin) {
    }

    /** 소요시간은 <b>분</b>이다 — 카카오 응답의 초를 {@code PlaceQueryServiceImpl}에서 변환해 담는다. */
    public record TaxiRoute(int fare, int distance, int durationMin) {
    }
}
