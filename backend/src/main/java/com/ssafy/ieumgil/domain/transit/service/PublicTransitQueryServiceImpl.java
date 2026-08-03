package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class PublicTransitQueryServiceImpl implements PublicTransitQueryService {

    private static final Set<String> SUPPORTED_MODES = Set.of("BUS", "SUBWAY");

    private final OdsayClient odsayClient;

    @Override
    public TransitResDTO.Route getRoute(
            double startLat, double startLng, double endLat, double endLng, String mode) {
        if (!SUPPORTED_MODES.contains(mode)) {
            throw new TransitException(TransitErrorCode.UNSUPPORTED_MODE);
        }

        return odsayClient
                .searchPublicTransitRoute(startLat, startLng, endLat, endLng, mode)
                .stream()
                .findFirst()
                .map(this::toRoute)
                .orElseThrow(() -> new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
    }

    @Override
    public TransitResDTO.Route getCombinedRoute(
            double startLat, double startLng, double endLat, double endLng) {
        // OdsayClient는 BUS/SUBWAY가 아닌 mode에 SearchPathType=0(통합)을 쓴다.
        return odsayClient.searchPublicTransitRoute(startLat, startLng, endLat, endLng, "TRANSIT")
                .stream()
                .findFirst()
                .map(this::toRoute)
                .orElseThrow(() -> new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
    }

    /**
     * 요금 필드가 없으면 {@code UNKNOWN}이다. 0으로 채우고 CONFIRMED를 붙이면
     * "무료"라고 단언하는 셈이 된다 — 실제로 서울→부산 KTX가 그렇게 나갔다.
     */
    private TransitResDTO.Route toRoute(OdsayRouteResponse.Path path) {
        OdsayRouteResponse.Info info = path.info();
        Integer fare = info.payment();
        return TransitResDTO.Route.builder()
                .durationMin(info.totalTime())
                .fare(fare)
                .intervalMin(info.totalIntervalTime())
                .distanceM(info.totalDistance())
                .estimated(false)
                .fareConfidence(fare == null
                        ? TransitResDTO.FareConfidence.UNKNOWN
                        : TransitResDTO.FareConfidence.CONFIRMED)
                .build();
    }
}
