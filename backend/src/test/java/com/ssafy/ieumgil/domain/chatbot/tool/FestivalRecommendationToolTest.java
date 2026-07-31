package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.RegionCode;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
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

        FestivalRecommendationResult result = tool.findFestivalsForCurrentTrip();

        assertThat(result.regionName()).isEqualTo("제주");
        assertThat(result.tripStartDate()).isEqualTo(start.toString());
        assertThat(result.tripEndDate()).isEqualTo(end.toString());
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

        FestivalSummary summary = FestivalSummary.from(festival);

        assertThat(summary.category()).isEqualTo("행사");
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

        assertThat(tool.findFestivalsForCurrentTrip().festivals()).isEmpty();
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

        FestivalRecommendationResult result = tool.findFestivalsForCurrentTrip();

        assertThat(result.festivals()).isEmpty();
        assertThat(result.regionName()).isEqualTo("서울");
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
                RegionCode.JEJU, start, end, festivalQueryService
        );

        List<FestivalSummary> result = tool.findFestivalsForCurrentTrip().festivals();

        assertThat(result).hasSize(10);
        assertThat(result).isSortedAccordingTo((a, b) -> a.eventStartDate().compareTo(b.eventStartDate()));
        assertThat(result.get(0).eventStartDate()).isEqualTo(start.plusDays(1).toString());
    }
}
