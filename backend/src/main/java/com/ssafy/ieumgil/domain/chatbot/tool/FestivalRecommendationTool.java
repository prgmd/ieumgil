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
    private final CandidateCollector candidateCollector;

    public FestivalRecommendationTool(RegionCode regionCode, LocalDate tripStartDate, LocalDate tripEndDate,
                                       FestivalQueryService festivalQueryService,
                                       CandidateCollector candidateCollector) {
        this.regionCode = regionCode;
        this.tripStartDate = tripStartDate;
        this.tripEndDate = tripEndDate;
        this.festivalQueryService = festivalQueryService;
        this.candidateCollector = candidateCollector;
    }

    @Tool(description = """
            Call this whenever the user asks for things to do, festivals, events, or activities to enjoy during their trip. This is the main tool for "what can I do / what's happening" style requests, even if the user does not literally say "festival".
            It takes no input: it automatically uses the current project's saved destination and travel dates. So call it directly and immediately — never ask the user for the destination or travel dates first.
            Do NOT call it for requests about restaurants, cafes, lodging, or transportation — those are not festivals/events.
            Returns the festivals/events for the current project's destination and travel period, along with the region name used; if the user asked about a different region, note the result may differ.
            If "available" is false the festival lookup is failing right now: say the event list cannot be loaded at the moment and ask the user to retry — never state that there are no festivals, which is what an empty list means only when "available" is true.
            Each item carries "tripOverlap": the exact dates on which that event actually overlaps the trip, already computed by the server. Report it as given. Never judge or describe how well an event fits the trip dates yourself — an event may overlap only a single day, so wording like "runs throughout your trip" or "fits your dates perfectly" is wrong unless tripOverlap literally says so.
            """)
    public FestivalRecommendationResult findFestivalsForCurrentTrip() {
        try {
            List<Festival> found = festivalQueryService
                    .findByRegionAndDateRange(regionCode.code(), tripStartDate, tripEndDate)
                    .stream()
                    .sorted(Comparator.comparing(Festival::getEventStartDate))
                    .limit(10)
                    .toList();
            // 모델에는 좌표를 넘기지 않으므로(요약만), 블록 생성용 원본은 수집기가 따로 보관한다
            found.forEach(candidateCollector::addFestival);
            List<FestivalSummary> festivals = found.stream()
                    .map(f -> FestivalSummary.from(f, tripStartDate, tripEndDate))
                    .toList();
            return new FestivalRecommendationResult(regionCode.regionName(), true, festivals);
        } catch (RuntimeException e) {
            log.warn("festival tool call failed for region={}", regionCode, e);
            // 대화는 계속되어야 하므로 예외를 삼키되, 모델이 사실대로 말할 수 있게 실패를 표시한다
            return FestivalRecommendationResult.unavailable(regionCode.regionName());
        }
    }
}
