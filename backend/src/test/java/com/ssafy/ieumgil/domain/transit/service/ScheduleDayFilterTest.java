package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainScheduleResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleDayFilterTest {

    @Test
    @DisplayName("매일 운행은 모든 요일에 참이다")
    void 매일은_항상_운행한다() {
        for (DayOfWeek day : DayOfWeek.values()) {
            assertThat(ScheduleDayFilter.runsOn("매일", day)).isTrue();
        }
    }

    @Test
    @DisplayName("토일 운행은 주말에만 참이다")
    void 토일은_주말에만_운행한다() {
        assertThat(ScheduleDayFilter.runsOn("토일", DayOfWeek.SATURDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("토일", DayOfWeek.SUNDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("토일", DayOfWeek.THURSDAY)).isFalse();
    }

    @Test
    @DisplayName("단일 요일 표기를 인식한다")
    void 단일_요일을_인식한다() {
        assertThat(ScheduleDayFilter.runsOn("목", DayOfWeek.THURSDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("목", DayOfWeek.FRIDAY)).isFalse();
    }

    @Test
    @DisplayName("여러 요일이 붙어 있는 표기를 인식한다")
    void 여러_요일_표기를_인식한다() {
        assertThat(ScheduleDayFilter.runsOn("월수금", DayOfWeek.WEDNESDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("월수금", DayOfWeek.TUESDAY)).isFalse();
    }

    @Test
    @DisplayName("평일 운행은 평일에만 참이다")
    void 평일은_평일에만_운행한다() {
        assertThat(ScheduleDayFilter.runsOn("평일", DayOfWeek.MONDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("평일", DayOfWeek.TUESDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("평일", DayOfWeek.WEDNESDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("평일", DayOfWeek.THURSDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("평일", DayOfWeek.FRIDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("평일", DayOfWeek.SATURDAY)).isFalse();
        assertThat(ScheduleDayFilter.runsOn("평일", DayOfWeek.SUNDAY)).isFalse();
    }

    @Test
    @DisplayName("주말 운행은 주말에만 참이다")
    void 주말은_주말에만_운행한다() {
        assertThat(ScheduleDayFilter.runsOn("주말", DayOfWeek.SATURDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("주말", DayOfWeek.SUNDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("주말", DayOfWeek.THURSDAY)).isFalse();
    }

    @Test
    @DisplayName("휴일·공휴일 표기는 모든 요일에 참이다")
    void 휴일은_항상_통과시킨다() {
        for (DayOfWeek day : DayOfWeek.values()) {
            assertThat(ScheduleDayFilter.runsOn("휴일", day)).isTrue();
            assertThat(ScheduleDayFilter.runsOn("공휴일", day)).isTrue();
        }
    }

    @Test
    @DisplayName("운행일을 모르면 운행한다고 본다")
    void 모르는_표기는_통과시킨다() {
        // 후보를 빼는 것보다 남기는 쪽이 낫다 — 사용자가 실제 시각을 확인할 수 있다
        assertThat(ScheduleDayFilter.runsOn(null, DayOfWeek.MONDAY)).isTrue();
        assertThat(ScheduleDayFilter.runsOn("부정기", DayOfWeek.MONDAY)).isTrue();
    }

    @Test
    @DisplayName("평일에는 weekday 요금, 주말에는 weekend 요금을 쓴다")
    void 요일에_맞는_요금을_고른다() {
        OdsayTrainScheduleResponse.DayFare fare =
                new OdsayTrainScheduleResponse.DayFare(59800, 63700, 65000);

        assertThat(ScheduleDayFilter.fareFor(fare, DayOfWeek.THURSDAY)).isEqualTo(59800);
        assertThat(ScheduleDayFilter.fareFor(fare, DayOfWeek.SATURDAY)).isEqualTo(63700);
        assertThat(ScheduleDayFilter.fareFor(fare, DayOfWeek.SUNDAY)).isEqualTo(63700);
    }

    @Test
    @DisplayName("해당 요일 요금이 비면 남은 값으로 폴백한다")
    void 요금이_비면_폴백한다() {
        OdsayTrainScheduleResponse.DayFare weekendOnly =
                new OdsayTrainScheduleResponse.DayFare(null, 63700, null);

        assertThat(ScheduleDayFilter.fareFor(weekendOnly, DayOfWeek.MONDAY)).isEqualTo(63700);
    }

    @Test
    @DisplayName("요금 정보가 아예 없으면 null이다")
    void 요금이_없으면_null이다() {
        assertThat(ScheduleDayFilter.fareFor(null, DayOfWeek.MONDAY)).isNull();
    }
}
