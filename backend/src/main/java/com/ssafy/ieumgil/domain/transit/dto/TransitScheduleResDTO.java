package com.ssafy.ieumgil.domain.transit.dto;

import lombok.Builder;

import java.util.List;

public class TransitScheduleResDTO {

    @Builder
    public record Terminal(int stationId, String stationName, double lat, double lng) {
    }

    @Builder
    public record TerminalSearchResult(
            int stationId,
            String stationName,
            double lat,
            double lng,
            List<Terminal> destinations
    ) {
    }

    @Builder
    public record TrainSchedule(
            String railName,
            String trainClass,
            int trainNo,
            String departureTime,
            String arrivalTime,
            String wasteTime,
            String runDay,
            Integer generalFare,
            Integer specialFare,
            Integer standingFare
    ) {
    }

    @Builder
    public record BusSchedule(
            int busClass,
            String departureTime,
            int wasteTimeMin,
            int fare
    ) {
    }

    @Builder
    public record FlightSchedule(
            String airline,
            String flightNo,
            String departureTime,
            String arrivalTime,
            String runDay
    ) {
    }
}
