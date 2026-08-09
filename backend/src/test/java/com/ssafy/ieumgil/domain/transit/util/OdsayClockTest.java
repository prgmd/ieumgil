package com.ssafy.ieumgil.domain.transit.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OdsayClockTest {

    @Test
    @DisplayName("24시를 넘긴 ODsay 표기를 읽는다 — LocalTime.parse는 여기서 던진다")
    void 자정_넘김_표기를_읽는다() {
        // 실측: 서울→부산 고속버스 51편 중 8편이 이 표기다("24:00"~"26:00")
        assertThat(OdsayClock.minutesOf("24:00")).isEqualTo(1440);
        assertThat(OdsayClock.minutesOf("24:10")).isEqualTo(1450);
        assertThat(OdsayClock.minutesOf("26:00")).isEqualTo(1560);
    }

    @Test
    @DisplayName("보통 시각도 자정 기준 분으로 읽는다")
    void 일반_시각을_읽는다() {
        assertThat(OdsayClock.minutesOf("00:00")).isZero();
        assertThat(OdsayClock.minutesOf("09:05")).isEqualTo(545);
        assertThat(OdsayClock.minutesOf("23:59")).isEqualTo(1439);
    }

    @Test
    @DisplayName("24시 넘김을 %1440으로 접지 않는다 — 접으면 다음 날 편이 오늘 새벽 편으로 보인다")
    void 자정_넘김을_접지_않는다() {
        assertThat(OdsayClock.format(1450)).isEqualTo("24:10");
        assertThat(OdsayClock.format(1560)).isEqualTo("26:00");
        assertThat(OdsayClock.format(545)).isEqualTo("09:05");
    }

    @Test
    @DisplayName("자정을 넘는 편의 소요를 음수로 내지 않는다")
    void 자정을_넘는_소요를_계산한다() {
        // 23:30 출발 05:10 도착 = 340분
        assertThat(OdsayClock.minutesBetween("23:30", "05:10")).isEqualTo(340);
        assertThat(OdsayClock.minutesBetween("16:00", "18:37")).isEqualTo(157);
        // 24시 넘김 표기끼리도 같은 축에서 계산된다
        assertThat(OdsayClock.minutesBetween("24:10", "26:00")).isEqualTo(110);
    }

    @Test
    @DisplayName("형식이 아니면 던진다 — 조용히 0분으로 떨어뜨리지 않는다")
    void 형식이_아니면_던진다() {
        assertThatThrownBy(() -> OdsayClock.minutesOf(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OdsayClock.minutesOf("9:5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OdsayClock.minutesOf("0900"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OdsayClock.minutesOf("09:60"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OdsayClock.minutesOf("ab:cd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("시(hour)에는 상한을 두지 않는다 — 상한을 잘못 잡으면 고치려는 문제가 재현된다")
    void 시에는_상한이_없다() {
        assertThat(OdsayClock.minutesOf("27:30")).isEqualTo(1650);
        assertThat(OdsayClock.minutesOf("30:00")).isEqualTo(1800);
    }
}
