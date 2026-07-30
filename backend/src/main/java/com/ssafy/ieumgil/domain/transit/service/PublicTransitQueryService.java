package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;

public interface PublicTransitQueryService {

    TransitResDTO.Route getRoute(double startLat, double startLng, double endLat, double endLng, String mode);
}
