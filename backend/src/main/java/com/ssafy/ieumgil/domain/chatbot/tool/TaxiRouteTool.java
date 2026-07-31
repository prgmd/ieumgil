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
            사용자가 두 장소 사이를 택시로 이동할 때 예상 요금·거리·시간을 물을 때 호출한다.
            출발지명과 도착지명은 대화에서 알 수 있는 가장 구체적인 이름으로 전달한다(모호한 "숙소"보다 실제 상호명이 있다면 그걸 우선한다).
            응답의 출발지명·도착지명은 실제로 검색되어 매칭된 장소명이므로, 사용자가 말한 곳과 다르게 매칭된 것 같으면 답변에서 확인을 요청한다.
            found가 false면 택시 경로를 찾지 못한 것이다.
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
