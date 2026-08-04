package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;

import java.util.List;

public interface PublicTransitQueryService {

    TransitResDTO.Route getRoute(double startLat, double startLng, double endLat, double endLng, String mode);

    /**
     * 버스·지하철을 함께 고려한 대중교통 경로.
     *
     * <p>{@link #getRoute}가 수단을 하나로 좁히는 것과 달리, 이쪽은 ODsay가 최적 조합을
     * 고르게 둔다. 교통 후보에서 "대중교통"은 하나여야 하기 때문이다 — 버스와 지하철을
     * 따로 후보로 내면 호출이 두 배가 되고 사용자에게도 선택지가 쓸데없이 늘어난다.
     */
    TransitResDTO.Route getCombinedRoute(double startLat, double startLng, double endLat, double endLng);

    /**
     * 대중교통 경로 <b>목록</b>. 매핑하지 않은 ODsay 원본을 준다.
     *
     * <p>{@link #getCombinedRoute}가 하나만 주는 것과 달리 여기는 전부 준다 — 후보 선정
     * (최단 시간·최저 요금·최소 환승…)이 여러 경로를 비교해야 하고, 비교에 필요한 값이
     * {@code info}와 {@code subPath} 양쪽에 흩어져 있어 미리 평탄화하면 정보를 잃는다.
     */
    List<OdsayRouteResponse.Path> getCombinedRoutes(double startLat, double startLng, double endLat, double endLng);
}
