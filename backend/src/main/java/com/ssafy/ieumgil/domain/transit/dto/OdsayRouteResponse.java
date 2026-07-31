package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayRouteResponse(Result result, List<OdsayError> error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Path> path) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(int pathType, Info info) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Info(
            int totalTime,
            int payment,
            int totalIntervalTime
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OdsayError(String code, String message) {
    }
}
