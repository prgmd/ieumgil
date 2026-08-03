package com.ssafy.ieumgil.domain.transit.dto;

import lombok.Builder;

public class TransitResDTO {

    @Builder
    public record Route(
            int durationMin,
            /** 요금을 모르면 null이다 — 0원과 구분한다 */
            Integer fare,
            Integer intervalMin,
            Integer distanceM,
            boolean estimated,
            FareConfidence fareConfidence
    ) {
    }

    public enum FareConfidence {
        /** 외부 API가 준 실제 요금 */
        CONFIRMED,
        /** 우리가 계산한 추정치 — 자차 연료비처럼 가정이 들어간 값 */
        ESTIMATE,
        /**
         * 알 수 없다.
         *
         * <p>두 경우에 쓴다. (1) 항공 — ODsay 시간표가 요금 필드를 아예 주지 않는다.
         * (2) 시외 경로 — ODsay 경로 API가 시내 경로에만 {@code payment}를 주고
         * 시외·항공·복합에는 그 필드가 없다.
         *
         * <p>이 값이 필요한 이유: 예전에는 필드 누락이 primitive {@code int}의 기본값 0으로
         * 들어가 "요금 0원 · CONFIRMED"로 나갔다. 사용자에게 "KTX 무료"로 보였다.
         */
        UNKNOWN
    }
}
