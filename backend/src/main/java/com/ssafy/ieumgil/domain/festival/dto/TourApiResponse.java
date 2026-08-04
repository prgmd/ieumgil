package com.ssafy.ieumgil.domain.festival.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TourApiResponse(Response response) {

    public record Response(Body body) {
    }

    /** totalCount는 배치가 몇 페이지를 돌아야 하는지 판단하는 근거다 — 없으면 덜 긁고도 성공처럼 끝난다. */
    public record Body(Items items, Integer totalCount) {
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
