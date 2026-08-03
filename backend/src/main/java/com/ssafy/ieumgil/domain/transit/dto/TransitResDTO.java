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
        /** 외부 API가 준 실제 요금 */
        CONFIRMED,
        /** 우리가 계산한 추정치 — 자차 연료비처럼 가정이 들어간 값 */
        ESTIMATE
    }
}
