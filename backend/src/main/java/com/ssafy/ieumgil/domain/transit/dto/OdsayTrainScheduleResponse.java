package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayTrainScheduleResponse(Result result, List<OdsayRouteResponse.OdsayError> error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            int count,
            String startStationName,
            String endStationName,
            List<Train> station
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Train(
            String railName,
            String trainClass,
            int trainNo,
            String departureTime,
            String arrivalTime,
            String wasteTime,
            String runDay,
            Fare fare,
            DayFare generalFare,
            DayFare specialFare,
            DayFare standingFare
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fare(Integer general, Integer special, Integer standing) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DayFare(Integer weekday, Integer weekend, Integer holiday) {
    }
}
