package com.ssafy.ieumgil.domain.transit.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConstantFuelPriceProviderTest {

    @Test
    @DisplayName("상수 유가는 국내 휘발유 가격의 상식 범위 안이다")
    void constantPriceIsInSaneRange() {
        // 값 자체를 고정하지 않는다 — 갱신하면 테스트가 깨져서는 안 된다.
        // 다만 단위를 잘못 넣는 사고(리터당 vs 100L당)는 잡아야 한다.
        assertThat(new ConstantFuelPriceProvider().pricePerLiter()).isBetween(1000, 3000);
    }
}
