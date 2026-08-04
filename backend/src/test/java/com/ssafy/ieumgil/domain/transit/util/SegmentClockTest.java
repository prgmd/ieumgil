package com.ssafy.ieumgil.domain.transit.util;

import java.time.LocalTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SegmentClockTest {

    @Test
    @DisplayName("첫 구간 기준은 Day 시작 시각 + 역 이동 버퍼다")
    void 첫_구간은_Day_시작에서_출발한다() {
        SegmentClock clock = new SegmentClock(LocalTime.of(9, 0));

        assertThat(clock.reference()).isEqualTo(LocalTime.of(9, 45));
    }

    @Test
    @DisplayName("앞 구간 이동시간과 뒤 블록 체류시간을 더해 다음 기준을 만든다")
    void 기준_시각을_누적한다() {
        SegmentClock clock = new SegmentClock(LocalTime.of(9, 0));
        // 구간0: 30분 이동 → 도착 09:30, 그 블록에 60분 체류 → 10:30 종료
        clock.advance(30, 60);

        assertThat(clock.reference()).isEqualTo(LocalTime.of(11, 15));
    }

    @Test
    @DisplayName("여러 구간을 연속으로 누적한다")
    void 여러_구간을_누적한다() {
        SegmentClock clock = new SegmentClock(LocalTime.of(9, 0));
        clock.advance(30, 60);
        clock.advance(20, 90);

        // 09:00 +30 이동 = 09:30, +60 체류 = 10:30, +20 이동 = 10:50, +90 체류 = 12:20, +45 버퍼
        assertThat(clock.reference()).isEqualTo(LocalTime.of(13, 5));
    }

    @Test
    @DisplayName("자정을 넘으면 다음 날로 넘어간 시각을 그대로 준다")
    void 자정을_넘겨도_자르지_않는다() {
        SegmentClock clock = new SegmentClock(LocalTime.of(22, 0));
        clock.advance(120, 60);

        // 22:00 +120 = 24:00 → 00:00, +60 = 01:00, +45 = 01:45
        assertThat(clock.reference()).isEqualTo(LocalTime.of(1, 45));
    }

    @Test
    @DisplayName("advance를 부르지 않으면 기준이 갱신되지 않는다")
    void advance_없이는_기준이_그대로다() {
        SegmentClock clock = new SegmentClock(LocalTime.of(9, 0));

        assertThat(clock.reference()).isEqualTo(LocalTime.of(9, 45));
        assertThat(clock.reference()).isEqualTo(LocalTime.of(9, 45));
    }
}
