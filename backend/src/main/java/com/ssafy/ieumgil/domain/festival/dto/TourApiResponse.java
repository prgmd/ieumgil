package com.ssafy.ieumgil.domain.festival.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TourApiResponse(Response response) {

    public record Response(Body body) {
    }

    public record Body(Items items) {
    }

    public record Items(List<Item> item) {
    }

    public record Item(
            String contentid,
            String title,
            String addr1,
            String addr2,
            String mapx,
            String mapy,
            String eventstartdate,
            String eventenddate,
            String firstimage,
            @JsonProperty("lDongRegnCd") String lDongRegnCd,
            @JsonProperty("lDongSignguCd") String lDongSignguCd,
            String lclsSystm2
    ) {
    }
}
