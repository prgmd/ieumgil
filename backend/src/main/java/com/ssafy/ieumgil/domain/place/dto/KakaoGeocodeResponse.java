package com.ssafy.ieumgil.domain.place.dto;

import java.util.List;

public record KakaoGeocodeResponse(List<Document> documents) {

    public record Document(String x, String y, RoadAddress road_address, Address address) {
    }

    public record RoadAddress(String address_name) {
    }

    public record Address(String address_name) {
    }
}
