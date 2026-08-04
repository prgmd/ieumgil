package com.ssafy.ieumgil.domain.festival.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** TourAPI detailCommon2 응답. 공통 상세 필드 중 homepage만 읽는다. */
public record TourApiDetailResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(Body body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(Items items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Items(List<Item> item) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String contentid, String homepage) {
    }
}
