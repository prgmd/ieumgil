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
            Call this when the user asks for specific places to recommend, such as restaurants, cafes, lodging, or tourist attractions.
            For festival, event, or things-to-do recommendations, use the regional festival recommendation tool instead of this one; this tool only covers places that operate year-round.
            Returns a list of places near the current project's destination matching the search query. Each result includes a link to view the place directly, so include the link in your answer as well.
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
