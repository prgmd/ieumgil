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
            /** category_group_name — 사람이 읽는 이름("카페") */
            String category,
            /** category_group_code — 프론트가 블록 카테고리를 정하는 키("CE7") */
            String categoryCode,
            /** 없으면 null. 카카오는 없을 때 빈 문자열을 준다 */
            String phone
    ) {
    }

    public record Address(String address, String roadAddress) {
    }

    /** 소요시간은 <b>분</b>이다 — 카카오 응답의 초를 {@code PlaceQueryServiceImpl}에서 변환해 담는다. */
    public record WalkingRoute(int distance, int durationMin) {
    }

    /**
     * 소요시간은 <b>분</b>이다 — 카카오 응답의 초를 {@code PlaceQueryServiceImpl}에서 변환해 담는다.
     * {@code fare}는 택시 요금, {@code toll}은 통행료다. 자차 후보는 택시 요금을 쓰지 않고
     * 통행료에 연료비 추정을 더하므로 둘을 따로 싣는다.
     */
    public record TaxiRoute(int fare, int toll, int distance, int durationMin) {
    }
}
