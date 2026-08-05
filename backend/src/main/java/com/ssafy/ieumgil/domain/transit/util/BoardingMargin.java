package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

/**
 * 시외 수단별 탑승 여유 시간(분).
 *
 * <p>시간표상 출발 시각 이전에 터미널·역·공항에 도착해 있어야 하는 여유분이다.
 * 항공은 보안검색·수하물 때문에 가장 길고(40분), 기차는 가장 짧다(10분).
 */
public final class BoardingMargin {

    private static final int AIR_MINUTES = 40;
    private static final int TRAIN_MINUTES = 10;
    private static final int EXPRESS_BUS_MINUTES = 15;

    private BoardingMargin() {
    }

    public static int minutesFor(TransitMode mode) {
        return switch (mode) {
            case AIR -> AIR_MINUTES;
            case TRAIN -> TRAIN_MINUTES;
            case EXPRESS_BUS -> EXPRESS_BUS_MINUTES;
            default -> throw new IllegalArgumentException("탑승 여유가 정의되지 않은 수단: " + mode);
        };
    }
}
