package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.RegionCode;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class FestivalRecommendationTool {

    private final RegionCode regionCode;
    private final LocalDate tripStartDate;
    private final LocalDate tripEndDate;
    private final FestivalQueryService festivalQueryService;

    public FestivalRecommendationTool(RegionCode regionCode, LocalDate tripStartDate, LocalDate tripEndDate,
                                       FestivalQueryService festivalQueryService) {
        this.regionCode = regionCode;
        this.tripStartDate = tripStartDate;
        this.tripEndDate = tripEndDate;
        this.festivalQueryService = festivalQueryService;
    }

    @Tool(description = """
            Call this whenever the user asks for things to do, festivals, events, or activities to enjoy during their trip. This is the main tool for "what can I do / what's happening" style requests, even if the user does not literally say "festival".
            It takes no input: it automatically uses the current project's saved destination and travel dates. So call it directly and immediately — never ask the user for the destination or travel dates first.
            Do NOT call it for requests about restaurants, cafes, lodging, or transportation — those are not festivals/events.
            Returns the festivals/events for the current project's destination and travel period, along with the region name and period used; if the user asked about a different region, note the result may differ.
            """)
    public FestivalRecommendationResult findFestivalsForCurrentTrip() {
        try {
            List<FestivalSummary> festivals = festivalQueryService
                    .findByRegionAndDateRange(regionCode.code(), tripStartDate, tripEndDate)
                    .stream()
                    .sorted(Comparator.comparing(Festival::getEventStartDate))
                    .limit(10)
                    .map(FestivalSummary::from)
                    .toList();
            return new FestivalRecommendationResult(
                    regionCode.regionName(), tripStartDate.toString(), tripEndDate.toString(), festivals
            );
        } catch (RuntimeException e) {
            log.warn("festival tool call failed for region={}", regionCode, e);
            return new FestivalRecommendationResult(
                    regionCode.regionName(), tripStartDate.toString(), tripEndDate.toString(), List.of()
            );
        }
    }
}
