package com.ssafy.ieumgil.domain.place.dto;

public record KakaoWalkingRouteResponse(String status, Route route) {

    public record Route(Properties properties) {
    }

    public record Properties(int totalDistance, int totalTime, String landingUrl) {
    }
}
