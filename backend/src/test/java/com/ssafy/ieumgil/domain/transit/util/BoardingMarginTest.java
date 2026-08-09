package com.ssafy.ieumgil.domain.transit.util;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO.TransitMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardingMarginTest {

    @Test
    @DisplayName("항공은 탑승 여유 40분")
    void 항공_탑승_여유() {
        assertThat(BoardingMargin.minutesFor(TransitMode.AIR)).isEqualTo(40);
    }

    @Test
    @DisplayName("기차는 탑승 여유 10분")
    void 기차_탑승_여유() {
        assertThat(BoardingMargin.minutesFor(TransitMode.TRAIN)).isEqualTo(10);
    }

    @Test
    @DisplayName("고속·시외버스는 탑승 여유 15분")
    void 고속_시외버스_탑승_여유() {
        assertThat(BoardingMargin.minutesFor(TransitMode.EXPRESS_BUS)).isEqualTo(15);
    }

    @Test
    @DisplayName("그 외 수단은 정의되지 않아 예외를 던진다")
    void 정의되지_않은_수단은_예외() {
        assertThatThrownBy(() -> BoardingMargin.minutesFor(TransitMode.CAR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
