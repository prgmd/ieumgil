package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Optional;

@Slf4j
public class TaxiRouteTool {

    private final String destination;
    private final PlaceQueryService placeQueryService;
    private final KakaoPlaceCoordinateResolver resolver;

    public TaxiRouteTool(String destination, PlaceQueryService placeQueryService, KakaoPlaceCoordinateResolver resolver) {
        this.destination = destination;
        this.placeQueryService = placeQueryService;
        this.resolver = resolver;
    }

    @Tool(description = """
            Call this when the user asks the estimated fare, distance, and time of a taxi ride between two places.
            Pass the start and end place names as the most specific names known from the conversation (prefer an actual place name over a vague word like "the accommodation" when one is available).
            The start/end names in the response are the place names that were actually searched and matched, so if a match looks different from what the user said, ask for confirmation in your answer.
            If found is false, no taxi route was found.
            This tool only finds places within the current project's destination (the destination is automatically prepended to the place name) — for intercity/interregional long-distance travel (e.g. "from Seoul to Busan"), use the train/bus/flight schedule tools instead of this one.
            If it reports that the route was not found, say so plainly. Never estimate the distance, duration, or fare yourself — a straight-line guess is not the travel figure the user asked for.
            """)
    public TaxiRouteResult getTaxiRoute(String startPlaceName, String endPlaceName) {
        try {
            Optional<PlaceResDTO.Place> start = resolver.resolve(destination, startPlaceName);
            Optional<PlaceResDTO.Place> end = resolver.resolve(destination, endPlaceName);
            if (start.isEmpty() || end.isEmpty()) {
                return TaxiRouteResult.empty(startPlaceName, endPlaceName);
            }
            return placeQueryService.getTaxiRoute(
                            start.get().lat(), start.get().lng(), end.get().lat(), end.get().lng())
                    .map(r -> TaxiRouteResult.of(start.get().name(), end.get().name(), r.fare(), r.distance(), r.duration()))
                    .orElseGet(() -> TaxiRouteResult.empty(startPlaceName, endPlaceName));
        } catch (RuntimeException e) {
            log.warn("taxi route tool call failed: {} -> {}", startPlaceName, endPlaceName, e);
            return TaxiRouteResult.empty(startPlaceName, endPlaceName);
        }
    }
}
