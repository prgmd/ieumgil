package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayBusScheduleResponse(Result result, List<OdsayRouteResponse.OdsayError> error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            int count,
            String startStationName,
            String endStationName,
            String firstTime,
            String lastTime,
            List<Bus> schedule
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Bus(
            int busClass,
            String departureTime,
            int wasteTime,
            int fare
    ) {
    }
}
