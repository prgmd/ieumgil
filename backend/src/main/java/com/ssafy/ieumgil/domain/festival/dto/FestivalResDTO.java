package com.ssafy.ieumgil.domain.festival.dto;

public class FestivalResDTO {

    /** url은 홈페이지가 없으면 null이다 — 프론트가 카카오 검색으로 폴백한다. */
    public record Homepage(String url) {
    }
}
