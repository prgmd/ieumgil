package com.ssafy.ieumgil.domain.place.dto;

public record KakaoWalkingRouteResponse(String status, Route route) {

    public record Route(Properties properties) {
    }

    /**
     * 필드명은 카카오 응답 그대로다(이름을 바꾸면 매핑이 깨진다).
     * {@code totalDistance}는 <b>미터</b>, {@code totalTime}은 <b>초</b> — 시청→명동 925m에서 894로 확인(2026-08-02).
     * 분으로 바꾸는 곳은 {@code PlaceQueryServiceImpl} 한 곳뿐이다.
     */
    public record Properties(int totalDistance, int totalTime, String landingUrl) {
    }
}
