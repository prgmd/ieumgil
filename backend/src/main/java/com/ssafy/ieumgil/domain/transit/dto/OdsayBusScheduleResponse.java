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
            /** 소요시간(분). primitive로 두면 누락이 0분이 되어 도착 시각이 출발 시각과 같아진다 */
            Integer wasteTime,
            /** 요금. primitive로 두면 누락이 0원이 되어 "고속버스 0원 확정"으로 나간다 */
            Integer fare
    ) {
    }
}
