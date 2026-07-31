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

        OdsayRouteResponse.Path path = odsayClient
                .searchPublicTransitRoute(startLat, startLng, endLat, endLng, mode)
                .orElseThrow(() -> new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));

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
