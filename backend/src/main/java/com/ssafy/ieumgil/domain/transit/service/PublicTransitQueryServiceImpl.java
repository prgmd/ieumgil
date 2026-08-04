package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
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

    @Override
    public List<OdsayRouteResponse.Path> getCombinedRoutes(
            double startLat, double startLng, double endLat, double endLng) {
        List<OdsayRouteResponse.Path> paths =
                odsayClient.searchPublicTransitRoute(startLat, startLng, endLat, endLng, "TRANSIT");
        if (paths.isEmpty()) {
            throw new TransitException(TransitErrorCode.ROUTE_NOT_FOUND);
        }
        return paths;
    }

    private TransitResDTO.Route toRoute(OdsayRouteResponse.Path path) {
        OdsayRouteResponse.Info info = path.info();
        return TransitResDTO.Route.builder()
                .durationMin(info.totalTime())
                .fare(info.payment())
                .intervalMin(info.totalIntervalTime())
                .distanceM(info.totalDistance())
                .estimated(false)
                .fareConfidence(TransitResDTO.confidenceOf(info.payment()))
                .build();
    }
}
