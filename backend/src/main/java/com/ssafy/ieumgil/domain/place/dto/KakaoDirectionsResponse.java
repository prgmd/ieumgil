package com.ssafy.ieumgil.domain.place.dto;

import java.util.List;

public record KakaoDirectionsResponse(List<Route> routes) {

    public record Route(int result_code, Summary summary) {
    }

    public record Summary(Fare fare, int distance, int duration) {
    }

    public record Fare(int taxi, int toll) {
    }
}
