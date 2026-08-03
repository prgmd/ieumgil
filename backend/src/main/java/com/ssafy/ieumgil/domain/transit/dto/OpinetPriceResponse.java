package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 오피넷 전국 평균 유가(avgAllPrice.do) 응답. 필드명이 전부 대문자라 매핑을 명시한다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpinetPriceResponse(@JsonProperty("RESULT") Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(@JsonProperty("OIL") List<Oil> oil) {
    }

    /** PRICE는 "1866.69" 같은 소수점 문자열이다 — 숫자 타입으로 받으면 안 된다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Oil(
            @JsonProperty("PRODCD") String prodcd,
            @JsonProperty("PRICE") String price
    ) {
    }
}
