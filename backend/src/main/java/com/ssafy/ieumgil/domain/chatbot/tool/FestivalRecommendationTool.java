package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.festival.RegionCode;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.time.LocalDate;
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
            사용자가 여행 기간 중 할 만한 것(축제, 행사, 이벤트, 즐길거리)을 추천해달라는 의도로
            물을 때 호출한다. 명시적으로 '축제'라는 단어를 쓰지 않아도 의도가 즐길거리 추천이면 호출한다.
            식당, 숙소, 교통수단처럼 축제/행사와 무관한 추천 요청에는 호출하지 않는다.
            현재 프로젝트의 목적지·여행기간에 해당하는 축제/행사 목록을 반환한다.
            """)
    public List<FestivalSummary> findFestivalsForCurrentTrip() {
        try {
            return festivalQueryService.findByRegionAndDateRange(regionCode.code(), tripStartDate, tripEndDate)
                    .stream()
                    .map(FestivalSummary::from)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("festival tool call failed for region={}", regionCode, e);
            return List.of();
        }
    }
}
