package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

/**
 * 지도 기반 추천 모드의 장소검색 tool (BOT-03).
 *
 * <p>일반 모드의 {@link KakaoPlaceSearchTool}과 다른 점은 검색어에 프로젝트 목적지를
 * 붙이지 않고 사용자가 보고 있는 지도 범위로 결과를 한정한다는 것이다. "보이는 범위"가
 * 곧 검색 범위이므로 목적지 문자열을 덧붙이면 오히려 범위와 어긋난다.
 */
@Slf4j
public class ViewportPlaceSearchTool {

    private final ChatbotReqDTO.MapContext viewport;
    private final PlaceQueryService placeQueryService;
    private final CandidateCollector candidateCollector;

    public ViewportPlaceSearchTool(ChatbotReqDTO.MapContext viewport, PlaceQueryService placeQueryService,
                                   CandidateCollector candidateCollector) {
        this.viewport = viewport;
        this.placeQueryService = placeQueryService;
        this.candidateCollector = candidateCollector;
    }

    @Tool(description = """
            Call this to find places inside the map area the user is currently looking at.
            Derive the search keyword from what the user asked (for example "quiet cafe" -> "cafe"). Do not add a region or city name — the visible map area already defines where to search.
            Returns places within that area only. Each result includes a link to view the place directly, so include the link in your answer as well.
            If it returns nothing, tell the user there is nothing matching in the current view and suggest moving or zooming out the map. Never fill the gap with places you know from memory — they may be far outside the visible area.
            """)
    public List<PlaceSearchSummary> searchPlacesInView(String keyword) {
        try {
            List<PlaceResDTO.Place> places = placeQueryService.searchPlacesInRect(
                    keyword, viewport.swLat(), viewport.swLng(), viewport.neLat(), viewport.neLng());
            // 모델에는 좌표를 넘기지 않으므로(요약만), 블록 생성용 원본은 수집기가 따로 보관한다
            places.forEach(candidateCollector::addPlace);
            return places.stream()
                    .map(PlaceSearchSummary::from)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("viewport place search tool call failed for keyword={}", keyword, e);
            return List.of();
        }
    }
}
