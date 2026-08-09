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
            /** 소요시간(분). ODsay가 주지 않으면 null이다 — 0분으로 두면 도착 시각을 지어내게 된다 */
            Integer wasteTimeMin,
            /** 요금. 없으면 null이다 — 0원과 구분한다 */
            Integer fare
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
