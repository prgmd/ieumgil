package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import org.springframework.ai.tool.annotation.Tool;

public class WalkingRouteTool extends AbstractRouteTool<WalkingRouteResult> {

    public WalkingRouteTool(String destination, PlaceQueryService placeQueryService, KakaoPlaceCoordinateResolver resolver) {
        super(destination, placeQueryService, resolver);
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
        return computeRoute(startPlaceName, endPlaceName,
                (start, end) -> placeQueryService.getWalkingRoute(start.lat(), start.lng(), end.lat(), end.lng())
                        .map(r -> WalkingRouteResult.of(start.name(), end.name(), r.distance(), r.durationMin()))
                        .orElse(null),
                WalkingRouteResult::empty,
                "walking");
    }
}
