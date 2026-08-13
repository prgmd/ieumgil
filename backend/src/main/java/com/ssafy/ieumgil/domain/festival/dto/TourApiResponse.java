package com.ssafy.ieumgil.domain.festival.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record TourApiResponse(Response response) {

    public record Response(Header header, Body body) {
    }

    /**
     * data.go.kr 공통 응답 헤더. 성공은 {@code resultCode "0000"} — 그 외 코드는 에러 봉투이며
     * body가 비어 온다. 이 필드가 없으면 에러가 "축제 0건"으로 위장돼 수집이 조용히 건너뛴다.
     */
    public record Header(String resultCode, String resultMsg) {
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
