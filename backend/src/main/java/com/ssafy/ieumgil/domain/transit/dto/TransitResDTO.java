package com.ssafy.ieumgil.domain.transit.dto;

import lombok.Builder;

public class TransitResDTO {

    @Builder
    public record Route(
            int durationMin,
            int fare,
            Integer intervalMin,
            boolean estimated,
            FareConfidence fareConfidence
    ) {
    }

    public enum FareConfidence {
        CONFIRMED
    }
}
