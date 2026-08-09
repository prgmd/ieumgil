package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayBusTerminalResponse(List<Terminal> result, List<OdsayRouteResponse.OdsayError> error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Terminal(
            int stationID,
            String stationName,
            double x,
            double y,
            boolean haveDestinationTerminals,
            List<Point> destinationTerminals
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(int stationID, String stationName, double x, double y) {
    }
}
