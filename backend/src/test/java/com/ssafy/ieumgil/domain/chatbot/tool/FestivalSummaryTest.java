package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.entity.Festival;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 축제가 여행 기간과 "얼마나" 겹치는지는 서버가 계산해 넘긴다.
 *
 * <p>조회 쿼리(findOverlapping)는 최소 하루 겹침만 보장하므로, 4일 여행에 마지막 하루만
 * 하는 축제도 결과에 들어온다. 이 값을 주지 않으면 모델이 두 기간을 눈대중으로 비교해
 * "기간 내내 즐길 수 있다"처럼 부풀린다(2026-07-31 품질 평가에서 실제 발생).
 */
class FestivalSummaryTest {

    private static Festival festival(LocalDate eventStart, LocalDate eventEnd) {
        return Festival.builder()
                .contentId("1")
                .title("제주 불빛축제")
                .category("EV01")
                .addr("제주특별자치도 제주시")
                .eventStartDate(eventStart)
                .eventEndDate(eventEnd)
                .build();
    }

    @Test
    @DisplayName("축제가 여행 기간을 모두 덮으면 여행 전체가 겹침 구간이다")
    void festivalCoveringWholeTripOverlapsEntireTrip() {
        Festival f = festival(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));

        FestivalSummary summary = FestivalSummary.from(f,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13));

        assertThat(summary.tripOverlap()).isEqualTo("2026-08-10 ~ 2026-08-13 (4 days)");
    }

    @Test
    @DisplayName("여행 마지막 하루만 겹치면 그 하루만 겹침으로 표기한다 — 부풀림 방지의 핵심 케이스")
    void festivalOnLastDayOverlapsOnlyThatDay() {
        Festival f = festival(LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 20));

        FestivalSummary summary = FestivalSummary.from(f,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13));

        assertThat(summary.tripOverlap()).isEqualTo("2026-08-13 (1 day)");
    }

    @Test
    @DisplayName("축제가 여행 기간 안에 완전히 들어가면 축제 기간이 곧 겹침 구간이다")
    void festivalInsideTripOverlapsItsOwnPeriod() {
        Festival f = festival(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 12));

        FestivalSummary summary = FestivalSummary.from(f,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13));

        assertThat(summary.tripOverlap()).isEqualTo("2026-08-11 ~ 2026-08-12 (2 days)");
    }

    @Test
    @DisplayName("여행 첫날에 끝나는 축제는 첫날 하루만 겹친다")
    void festivalEndingOnFirstTripDayOverlapsOnlyThatDay() {
        Festival f = festival(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

        FestivalSummary summary = FestivalSummary.from(f,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13));

        assertThat(summary.tripOverlap()).isEqualTo("2026-08-10 (1 day)");
    }

    @Test
    @DisplayName("축제 기간 자체는 그대로 유지한다 — 겹침 구간과 별개 정보다")
    void keepsRawEventPeriod() {
        Festival f = festival(LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 20));

        FestivalSummary summary = FestivalSummary.from(f,
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13));

        assertThat(summary.eventStartDate()).isEqualTo("2026-08-13");
        assertThat(summary.eventEndDate()).isEqualTo("2026-08-20");
    }
}
