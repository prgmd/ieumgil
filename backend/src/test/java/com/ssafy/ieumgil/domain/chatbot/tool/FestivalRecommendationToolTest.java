package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.RegionCode;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                RegionCode.JEJU, start, end, festivalQueryService
        );

        List<FestivalSummary> result = tool.findFestivalsForCurrentTrip();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("제주 불빛축제");
        assertThat(result.get(0).addr()).isEqualTo("제주특별자치도 제주시");
        assertThat(result.get(0).eventStartDate()).isEqualTo(start);
    }

    @Test
    void returnsEmptyListWhenNoFestivalsMatch() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 3);
        when(festivalQueryService.findByRegionAndDateRange(any(), any(), any()))
                .thenReturn(List.of());

        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                RegionCode.SEOUL, start, end, festivalQueryService
        );

        assertThat(tool.findFestivalsForCurrentTrip()).isEmpty();
    }

    @Test
    void returnsEmptyListInsteadOfThrowingWhenQueryFails() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 3);
        when(festivalQueryService.findByRegionAndDateRange(any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        FestivalRecommendationTool tool = new FestivalRecommendationTool(
                RegionCode.SEOUL, start, end, festivalQueryService
        );

        assertThat(tool.findFestivalsForCurrentTrip()).isEmpty();
    }
}
