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
                .map(this::toRoute)
                .orElseThrow(() -> new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
    }

    @Override
    public TransitResDTO.Route getCombinedRoute(
            double startLat, double startLng, double endLat, double endLng) {
        // OdsayClient는 BUS/SUBWAY가 아닌 mode에 SearchPathType=0(통합)을 쓴다.
        return odsayClient
                .searchPublicTransitRoute(startLat, startLng, endLat, endLng, "TRANSIT")
                .map(this::toRoute)
                .orElseThrow(() -> new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));
    }

    // getRoute/getCombinedRoute는 mode 결정 방식만 다를 뿐 ODsay 응답 구조는 동일하므로 매핑을 공통화한다.
    private TransitResDTO.Route toRoute(OdsayRouteResponse.Path path) {
        OdsayRouteResponse.Info info = path.info();
        return TransitResDTO.Route.builder()
                .durationMin(info.totalTime())
                .fare(info.payment())
                .intervalMin(info.totalIntervalTime())
                .estimated(false)
                .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED)
                .build();
    }
}
