package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FestivalRecommendationToolTest {

    @Mock
    private FestivalQueryService festivalQueryService;

    @Test
    void returnsSummariesMappedFromMatchingFestivals() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 3);
        Festival festival = Festival.builder()
                .contentId("123")
                .title("제주 불빛축제")
                .category("EV01")
                .lDongRegnCd("50")
                .addr("제주특별자치도 제주시")
                .eventStartDate(start)
                .eventEndDate(end)
                .build();
        when(festivalQueryService.findByRegionAndDateRange(eq("50"), eq(start), eq(end)))
                .thenReturn(List.of(festival));

        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                start, end, festivalQueryService, new CandidateCollector());

        FestivalRecommendationResult result = tool.findFestivalsForCurrentTrip("제주");

        assertThat(result.regionName()).isEqualTo("제주");
        assertThat(result.festivals()).hasSize(1);
        assertThat(result.festivals().get(0).title()).isEqualTo("제주 불빛축제");
        assertThat(result.festivals().get(0).category()).isEqualTo("축제");
        assertThat(result.festivals().get(0).addr()).isEqualTo("제주특별자치도 제주시");
        assertThat(result.festivals().get(0).eventStartDate()).isEqualTo(start.toString());
    }

    @Test
    void unrecognizedCategoryCodeFallsBackToEtc() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 3);
        Festival festival = Festival.builder()
                .contentId("999")
                .title("정체불명 이벤트")
                .category("EV99")
                .lDongRegnCd("50")
                .addr("제주특별자치도 제주시")
                .eventStartDate(start)
                .eventEndDate(end)
                .build();

        FestivalSummary summary = FestivalSummary.from(festival, start, end);

        assertThat(summary.category()).isEqualTo("행사");
    }

    @Test
    void returnsEmptyListWhenNoFestivalsMatch() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 3);
        when(festivalQueryService.findByRegionAndDateRange(any(), any(), any()))
                .thenReturn(List.of());

        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                start, end, festivalQueryService, new CandidateCollector());

        FestivalRecommendationResult result = tool.findFestivalsForCurrentTrip("서울");

        assertThat(result.available()).isTrue();
        assertThat(result.festivals()).isEmpty();
    }

    @Test
    @DisplayName("조회가 실패하면 빈 목록으로 떨어뜨리되 available=false로 구분한다")
    void returnsUnavailableInsteadOfThrowingWhenQueryFails() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 3);
        when(festivalQueryService.findByRegionAndDateRange(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                start, end, festivalQueryService, new CandidateCollector());

        FestivalRecommendationResult result = tool.findFestivalsForCurrentTrip("서울");

        // "행사가 없다"와 "지금 못 찾는다"가 같은 모양이면 모델이 없다고 단언한다
        assertThat(result.available()).isFalse();
        assertThat(result.festivals()).isEmpty();
        assertThat(result.regionName()).isEqualTo("서울");
    }

    @Test
    @DisplayName("설명이 available=false(조회 실패)와 '행사 없음'을 구분하도록 지시한다")
    void descriptionDistinguishesUnavailableFromNoFestivals() throws NoSuchMethodException {
        String description = FestivalRecommendationTool.class.getMethod("findFestivalsForCurrentTrip", String.class)
                .getAnnotation(org.springframework.ai.tool.annotation.Tool.class).description();

        assertThat(description).contains("available");
    }

    @Test
    void cappedAtTenResultsSortedByStartDateAscending() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);
        List<Festival> festivals = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            // insert in descending start-date order to prove sorting, not just pass-through
            LocalDate eventStart = start.plusDays(15 - i);
            festivals.add(Festival.builder()
                    .contentId("id-" + i)
                    .title("축제-" + i)
                    .category("EV01")
                    .lDongRegnCd("50")
                    .addr("제주특별자치도")
                    .eventStartDate(eventStart)
                    .eventEndDate(eventStart.plusDays(1))
                    .build());
        }
        when(festivalQueryService.findByRegionAndDateRange(eq("50"), eq(start), eq(end)))
                .thenReturn(festivals);

        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                start, end, festivalQueryService, new CandidateCollector());

        List<FestivalSummary> result = tool.findFestivalsForCurrentTrip("제주").festivals();

        assertThat(result).hasSize(10);
        assertThat(result).isSortedAccordingTo((a, b) -> a.eventStartDate().compareTo(b.eventStartDate()));
        assertThat(result.get(0).eventStartDate()).isEqualTo(start.plusDays(1).toString());
    }

    @Test
    @DisplayName("조회된 축제가 수집기에 담긴다 — 응답 candidates[]의 원천이다")
    void festivalsAreCollectedAsCandidates() {
        LocalDate start = LocalDate.of(2026, 8, 10);
        LocalDate end = LocalDate.of(2026, 8, 13);
        Festival festival = Festival.builder()
                .contentId("555").title("제주 불빛축제").category("EV01")
                .lDongRegnCd("50").addr("제주 제주시")
                .lat(33.5).lng(126.5)
                .eventStartDate(start).eventEndDate(end)
                .build();
        when(festivalQueryService.findByRegionAndDateRange("50", start, end)).thenReturn(List.of(festival));
        CandidateCollector collector = new CandidateCollector();
        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                start, end, festivalQueryService, collector);

        tool.findFestivalsForCurrentTrip("제주");

        assertThat(collector.candidates()).hasSize(1);
        assertThat(collector.candidates().get(0).placeId()).isEqualTo("555");
    }

    @Test
    @DisplayName("모델이 넘긴 시/도 이름을 지역코드로 풀어 조회한다")
    void resolvesProvinceArgToCode() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 3);
        when(festivalQueryService.findByRegionAndDateRange(eq("52"), eq(start), eq(end)))
                .thenReturn(List.of());

        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                start, end, festivalQueryService, new CandidateCollector());

        FestivalRecommendationResult result = tool.findFestivalsForCurrentTrip("전북");

        assertThat(result.available()).isTrue();
        assertThat(result.regionName()).isEqualTo("전북");
    }

    @Test
    @DisplayName("시/도로 해석 안 되는 region(시·읍면리)은 조회 없이 available=false")
    void unresolvableRegionReturnsUnavailable() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 3);

        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                start, end, festivalQueryService, new CandidateCollector());

        FestivalRecommendationResult result = tool.findFestivalsForCurrentTrip("전주");

        assertThat(result.available()).isFalse();
        assertThat(result.festivals()).isEmpty();
        verify(festivalQueryService, never()).findByRegionAndDateRange(any(), any(), any());
    }
}
