package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

@Slf4j
public class KakaoPlaceSearchTool {

    private final String destination;
    private final PlaceQueryService placeQueryService;
    private final CandidateCollector candidateCollector;

    public KakaoPlaceSearchTool(String destination, PlaceQueryService placeQueryService,
                                CandidateCollector candidateCollector) {
        this.destination = destination;
        this.placeQueryService = placeQueryService;
        this.candidateCollector = candidateCollector;
    }

    @Tool(description = """
            Call this when the user asks for specific places to recommend, such as restaurants, cafes, lodging, or tourist attractions.
            For festival, event, or things-to-do recommendations, use the regional festival recommendation tool instead of this one; this tool only covers places that operate year-round.
            Returns a list of places near the current project's destination matching the search query. Each result includes a link to view the place directly, so include the link in your answer as well.
            """)
    public List<PlaceSearchSummary> searchPlaces(String keyword) {
        try {
            String query = destination + " " + keyword;
            List<PlaceResDTO.Place> places = placeQueryService.searchPlaces(query, null, null);
            // 모델에는 좌표를 넘기지 않으므로(요약만), 블록 생성용 원본은 수집기가 따로 보관한다
            places.forEach(candidateCollector::addPlace);
            return places.stream()
                    .map(PlaceSearchSummary::from)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("place search tool call failed for keyword={}", keyword, e);
            return List.of();
        }
    }
}
