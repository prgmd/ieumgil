package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayFlightScheduleResponse(Result result, List<OdsayRouteResponse.OdsayError> error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            int count,
            String startStationName,
            String endStationName,
            List<Flight> station
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Flight(
            String airline,
            String departureTime,
            String arrivalTime,
            String flight,
            String runDay
    ) {
    }
}
