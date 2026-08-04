package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;

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
}
