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
}
