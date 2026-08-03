package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Optional;

@Slf4j
public class WalkingRouteTool {

    private final String destination;
    private final PlaceQueryService placeQueryService;
    private final KakaoPlaceCoordinateResolver resolver;

    public WalkingRouteTool(String destination, PlaceQueryService placeQueryService, KakaoPlaceCoordinateResolver resolver) {
        this.destination = destination;
        this.placeQueryService = placeQueryService;
        this.resolver = resolver;
    }

    @Tool(description = """
            Call this when the user asks the distance and time to walk between two places.
            Pass the start and end place names as the most specific names known from the conversation (prefer an actual place name over a vague word like "the accommodation" when one is available).
            The start/end names in the response are the place names that were actually searched and matched, so if a match looks different from what the user said, ask for confirmation in your answer.
            If found is false, no walking route between the two places was found.
            This tool only finds places within the current project's destination (the destination is automatically prepended to the place name) — for intercity/interregional long-distance travel (e.g. "from Seoul to Busan"), use the train/bus/flight schedule tools instead of this one.
            If it reports that the route was not found, say so plainly. Never estimate the distance, duration, or fare yourself — a straight-line guess is not the travel figure the user asked for.
            """)
    public WalkingRouteResult getWalkingRoute(String startPlaceName, String endPlaceName) {
        try {
            Optional<PlaceResDTO.Place> start = resolver.resolve(destination, startPlaceName);
            Optional<PlaceResDTO.Place> end = resolver.resolve(destination, endPlaceName);
            if (start.isEmpty() || end.isEmpty()) {
                return WalkingRouteResult.empty(startPlaceName, endPlaceName);
            }
            return placeQueryService.getWalkingRoute(
                            start.get().lat(), start.get().lng(), end.get().lat(), end.get().lng())
                    .map(r -> WalkingRouteResult.of(start.get().name(), end.get().name(), r.distance(), r.durationMin()))
                    .orElseGet(() -> WalkingRouteResult.empty(startPlaceName, endPlaceName));
        } catch (RuntimeException e) {
            log.warn("walking route tool call failed: {} -> {}", startPlaceName, endPlaceName, e);
            return WalkingRouteResult.empty(startPlaceName, endPlaceName);
        }
    }
}
