package com.ssafy.ieumgil.domain.transit.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HaversineTest {

    @Test
    @DisplayName("서울시청에서 강남역까지 직선거리는 약 8.8km다")
    void distanceBetweenCityHallAndGangnam() {
        double d = Haversine.distanceMeters(37.5666, 126.9784, 37.4979, 127.0276);

        // 실제 도로 거리는 10.7km, 직선은 그보다 짧다.
        // 표준 Haversine 계산값은 약 8785m — 상한을 8700m로 두면 정상 구현도 실패해
        // 8900m로 조정(실측값 기준 여유 포함).
        assertThat(d).isBetween(7900.0, 8900.0);
    }

    @Test
    @DisplayName("같은 좌표는 0m다")
    void sameCoordinateIsZero() {
        assertThat(Haversine.distanceMeters(37.5, 127.0, 37.5, 127.0)).isZero();
    }

    @Test
    @DisplayName("100m 남짓 떨어진 두 점을 구분한다 — 근거리 임계 판정에 쓰인다")
    void shortDistanceIsPrecise() {
        // 위도 0.001도 ≈ 111m
        double d = Haversine.distanceMeters(37.5000, 127.0, 37.5010, 127.0);

        assertThat(d).isBetween(105.0, 120.0);
    }
}
