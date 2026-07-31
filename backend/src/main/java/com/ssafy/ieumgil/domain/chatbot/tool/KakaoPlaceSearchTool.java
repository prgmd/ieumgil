package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

@Slf4j
public class KakaoPlaceSearchTool {

    private final String destination;
    private final PlaceQueryService placeQueryService;

    public KakaoPlaceSearchTool(String destination, PlaceQueryService placeQueryService) {
        this.destination = destination;
        this.placeQueryService = placeQueryService;
    }

    @Tool(description = """
            사용자가 맛집, 카페, 숙소, 관광명소 등 구체적인 장소를 추천해달라고 물을 때 호출한다.
            축제, 행사, 이벤트 추천 요청에는 이 툴 대신 지역 축제 추천 툴을 쓴다 — 이 툴은
            상시 운영되는 장소만 다룬다.
            현재 프로젝트의 목적지 근처에서 검색어에 맞는 장소 목록을 반환한다. 결과에는 각 장소를
            직접 확인할 수 있는 링크가 포함되므로, 답변에 링크도 함께 안내한다.
            """)
    public List<PlaceSearchSummary> searchPlaces(String keyword) {
        try {
            String query = destination + " " + keyword;
            return placeQueryService.searchPlaces(query, null, null).stream()
                    .map(PlaceSearchSummary::from)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("place search tool call failed for keyword={}", keyword, e);
            return List.of();
        }
    }
}
