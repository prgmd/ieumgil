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
            사용자가 여행 기간 중 할 만한 것(축제, 행사, 이벤트, 즐길거리)을 추천해달라는 의도로
            물을 때 호출한다. 명시적으로 '축제'라는 단어를 쓰지 않아도 의도가 즐길거리 추천이면 호출한다.
            식당, 숙소, 교통수단처럼 축제/행사와 무관한 추천 요청에는 호출하지 않는다.
            현재 프로젝트의 목적지·여행기간에 해당하는 축제/행사 목록을 반환한다.
            결과에는 조회 기준이 된 지역명과 여행 기간도 함께 포함되므로, 사용자가 다른 지역을 물었다면
            결과가 그 지역과 다를 수 있음을 알고 답변에 반영한다.
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
