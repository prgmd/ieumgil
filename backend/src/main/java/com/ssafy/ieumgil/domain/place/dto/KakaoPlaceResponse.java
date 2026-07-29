package com.ssafy.ieumgil.domain.place.dto;

import java.util.List;

public record KakaoPlaceResponse(List<Document> documents) {

    public record Document(
            String id,
            String place_name,
            String category_group_name,
            String address_name,
            String road_address_name,
            String x,
            String y
    ) {
    }
}
