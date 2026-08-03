package com.ssafy.ieumgil.domain.place.dto;

import java.util.List;

public record KakaoDirectionsResponse(List<Route> routes) {

    public record Route(int result_code, Summary summary) {
    }

    /**
     * 필드명은 카카오 응답 그대로다(이름을 바꾸면 매핑이 깨진다).
     * {@code distance}는 <b>미터</b>, {@code duration}은 <b>초</b> — 서울시청→강남역 10.7km에서 1217로 확인(2026-08-02).
     * 분으로 바꾸는 곳은 {@code PlaceQueryServiceImpl} 한 곳뿐이다.
     */
    public record Summary(Fare fare, int distance, int duration) {
    }

    public record Fare(int taxi, int toll) {
    }
}
