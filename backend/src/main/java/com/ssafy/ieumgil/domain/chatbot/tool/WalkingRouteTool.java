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
            사용자가 두 장소 사이를 걸어서 이동할 때 걸리는 거리·시간을 물을 때 호출한다.
            출발지명과 도착지명을 그대로 전달한다(예: "숙소", "○○카페").
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
                    .map(r -> WalkingRouteResult.of(start.get().name(), end.get().name(), r.distance(), r.duration()))
                    .orElseGet(() -> WalkingRouteResult.empty(startPlaceName, endPlaceName));
        } catch (RuntimeException e) {
            log.warn("walking route tool call failed: {} -> {}", startPlaceName, endPlaceName, e);
            return WalkingRouteResult.empty(startPlaceName, endPlaceName);
        }
    }
}
