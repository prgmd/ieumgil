package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여행 메타데이터를 시스템 프롬프트 꼬리로 만드는 포맷터.
 * 모델이 날짜 계산을 하지 않도록 일수를 서버가 미리 계산해 넣는 것이 핵심이다.
 */
class TripContextBuilderTest {

    @Test
    @DisplayName("모든 값이 있으면 전 항목을 담고, 일수는 서버가 계산해 넣는다")
    void buildsFullBlockWithServerCalculatedDayCount() {
        Project project = Project.builder()
                .destination("제주")
                .startDate(LocalDate.of(2026, 8, 10))
                .endDate(LocalDate.of(2026, 8, 13))
                .transportPrefs(List.of(TransportPref.CAR))
                .keywords(List.of("오름", "카페"))
                .build();

        String block = TripContextBuilder.build(project, 4);

        assertThat(block).contains("destination: 제주");
        assertThat(block).contains("dates: 2026-08-10 ~ 2026-08-13 (4 days)");
        assertThat(block).contains("headcount: 4");
        assertThat(block).contains("transport: 자가용");
        assertThat(block).contains("keywords: 오름, 카페");
    }

    @Test
    @DisplayName("당일치기는 1 day로 센다")
    void sameDayTripCountsAsOneDay() {
        Project project = Project.builder()
                .destination("부산")
                .startDate(LocalDate.of(2026, 8, 10))
                .endDate(LocalDate.of(2026, 8, 10))
                .build();

        assertThat(TripContextBuilder.build(project, 2)).contains("(1 day)");
    }

    @Test
    @DisplayName("대중교통 선호는 PUBLIC이 아니라 사람이 읽는 말로 넣는다")
    void publicTransportIsLocalized() {
        Project project = Project.builder()
                .destination("서울")
                .transportPrefs(List.of(TransportPref.PUBLIC))
                .build();

        assertThat(TripContextBuilder.build(project, 3)).contains("transport: 대중교통");
    }

    @Test
    @DisplayName("목적지가 없으면 (unset)으로 명시한다 — 모델이 되물을 근거가 된다")
    void missingDestinationIsMarkedUnset() {
        Project project = Project.builder()
                .startDate(LocalDate.of(2026, 8, 10))
                .endDate(LocalDate.of(2026, 8, 13))
                .build();

        assertThat(TripContextBuilder.build(project, 4)).contains("destination: (unset)");
    }

    @Test
    @DisplayName("기간이 없으면 (unset)으로 명시하고 일수는 넣지 않는다")
    void missingDatesAreMarkedUnsetWithoutDayCount() {
        Project project = Project.builder()
                .destination("제주")
                .build();

        String block = TripContextBuilder.build(project, 4);

        assertThat(block).contains("dates: (unset)");
        assertThat(block).doesNotContain("days)");
    }

    @Test
    @DisplayName("키워드가 비어 있으면 줄 자체를 넣지 않는다 — 수집 경로가 없어 대개 비어 있다")
    void emptyKeywordsOmitTheLine() {
        Project withNull = Project.builder().destination("제주").keywords(null).build();
        Project withEmpty = Project.builder().destination("제주").keywords(List.of()).build();

        assertThat(TripContextBuilder.build(withNull, 4)).doesNotContain("keywords");
        assertThat(TripContextBuilder.build(withEmpty, 4)).doesNotContain("keywords");
    }

    @Test
    @DisplayName("교통수단이 없으면 줄 자체를 넣지 않는다")
    void missingTransportOmitsTheLine() {
        Project project = Project.builder().destination("제주").build();

        assertThat(TripContextBuilder.build(project, 4)).doesNotContain("transport");
    }

    @Test
    @DisplayName("인원을 못 구했으면 (unset)으로 명시한다")
    void missingHeadcountIsMarkedUnset() {
        Project project = Project.builder().destination("제주").build();

        assertThat(TripContextBuilder.build(project, null)).contains("headcount: (unset)");
    }

    @Test
    @DisplayName("목적지와 기간이 모두 없으면 블록 자체를 만들지 않는다 — 빈 껍데기를 프롬프트에 넣지 않는다")
    void omitsWholeBlockWhenNothingUseful() {
        Project project = Project.builder().build();

        assertThat(TripContextBuilder.build(project, null)).isEmpty();
    }

    @Test
    @DisplayName("목표 예산이 있으면 담고, 없으면 줄을 넣지 않는다")
    void includesTargetBudgetOnlyWhenSet() {
        Project withBudget = Project.builder().destination("제주").targetBudget(300000).build();
        Project without = Project.builder().destination("제주").build();

        assertThat(TripContextBuilder.build(withBudget, 4)).contains("targetBudget: 300000");
        assertThat(TripContextBuilder.build(without, 4)).doesNotContain("targetBudget");
    }
}
