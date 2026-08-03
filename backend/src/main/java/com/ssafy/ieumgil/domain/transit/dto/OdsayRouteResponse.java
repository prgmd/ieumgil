package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayRouteResponse(Result result, List<OdsayError> error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Path> path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(int pathType, Info info, List<SubPath> subPath) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Info(
            int totalTime,
            /**
             * 요금. <b>시내 경로에만 온다.</b> 시외(pathType 11)·항공(13)·복합(20)에는
             * 이 필드가 응답에 아예 없다 — primitive로 두면 누락이 0원이 된다.
             */
            Integer payment,
            /** 배차 간격. payment와 같은 이유로 Integer다 */
            Integer totalIntervalTime,
            /** 경로 실거리(m). 시내·시외 모두 온다 */
            Integer totalDistance,
            Integer totalWalk,
            Integer busTransitCount,
            Integer subwayTransitCount,
            String firstStartStation,
            String lastEndStation
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubPath(
            int trafficType,
            Integer sectionTime,
            Integer distance,
            String startName,
            String endName,
            List<Lane> lane
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(String name, String busNo, String airline) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OdsayError(String code, String message) {
    }
}
