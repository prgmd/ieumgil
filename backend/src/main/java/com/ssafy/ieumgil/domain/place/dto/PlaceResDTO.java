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
            /**
             * category_name 원문 — 계층 전체("음식점 &gt; 카페 &gt; 커피전문점 &gt; 스타벅스").
             *
             * <p>마지막 마디에 브랜드가 오는 경우가 있어 프랜차이즈 판별에 쓴다. 카카오가
             * 안 줄 수 있으므로 nullable 이다.
             */
            String categoryPath,
            /** 없으면 null. 카카오는 없을 때 빈 문자열을 준다 */
            String phone
    ) {
    }

    public record Address(String address, String roadAddress) {
    }

    /** 주소 → 좌표 결과. 도로명·지번은 카카오가 주는 대로 싣고, 없으면 빈 문자열이다 */
    public record Geocode(Double lat, Double lng, String roadAddress, String jibunAddress) {
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
