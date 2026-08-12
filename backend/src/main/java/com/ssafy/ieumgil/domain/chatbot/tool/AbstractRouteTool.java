package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.BiFunction;

/**
 * 도보·택시 경로 tool의 공통 뼈대. 두 tool은 목적지 접두 검색으로 출발·도착을 좌표로 바꾼 뒤
 * 경로를 계산한다는 흐름이 같고, 실제 경로 API 호출과 결과 매핑만 다르다.
 *
 * <p>LLM이 보는 것은 하위 클래스의 {@code @Tool} 메서드(이름·설명·응답 record)뿐이다.
 * 이 클래스는 내부 구현만 담으며 tool 계약에는 관여하지 않는다.
 */
@Slf4j
abstract class AbstractRouteTool<R> {

    private final String destination;
    protected final PlaceQueryService placeQueryService;
    private final KakaoPlaceCoordinateResolver resolver;

    protected AbstractRouteTool(String destination, PlaceQueryService placeQueryService,
                                KakaoPlaceCoordinateResolver resolver) {
        this.destination = destination;
        this.placeQueryService = placeQueryService;
        this.resolver = resolver;
    }

    /**
     * 출발·도착을 좌표로 해석하고 경로를 계산한다. 둘 중 하나라도 못 찾거나 경로 매핑이 null이면
     * {@code emptyFactory}로, 실행 중 예외가 나면 로그를 남기고 역시 empty로 떨어진다.
     *
     * @param routeMapper  해석된 출발·도착 장소로 경로를 계산해 결과를 만든다. 경로가 없으면 null.
     * @param emptyFactory 출발·도착 이름으로 "못 찾음" 결과를 만든다.
     * @param label        실패 로그에 쓰는 tool 구분자(예: {@code walking}, {@code taxi}).
     */
    protected R computeRoute(String startPlaceName, String endPlaceName,
                             BiFunction<PlaceResDTO.Place, PlaceResDTO.Place, R> routeMapper,
                             BiFunction<String, String, R> emptyFactory,
                             String label) {
        try {
            Optional<PlaceResDTO.Place> start = resolver.resolve(destination, startPlaceName);
            Optional<PlaceResDTO.Place> end = resolver.resolve(destination, endPlaceName);
            if (start.isEmpty() || end.isEmpty()) {
                return emptyFactory.apply(startPlaceName, endPlaceName);
            }
            R route = routeMapper.apply(start.get(), end.get());
            return route != null ? route : emptyFactory.apply(startPlaceName, endPlaceName);
        } catch (RuntimeException e) {
            log.warn("{} route tool call failed: {} -> {}", label, startPlaceName, endPlaceName, e);
            return emptyFactory.apply(startPlaceName, endPlaceName);
        }
    }
}
