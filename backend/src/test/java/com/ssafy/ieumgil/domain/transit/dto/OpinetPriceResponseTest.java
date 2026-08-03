package com.ssafy.ieumgil.domain.transit.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 맨 ObjectMapper(Boot 설정이 안 붙은 매퍼)로 오피넷 실응답 형태를 파싱한다.
 * @RestClientTest가 주입하는 관대한 ObjectMapper는 이 회귀를 못 잡는다 — 여기서만 재현된다.
 */
class OpinetPriceResponseTest {

    private static final String RAW_RESPONSE = """
            {"RESULT": {"OIL":[
            {"TRADE_DT":"20260803","PRODCD":"B034","PRODNM":"고급휘발유","PRICE":"2341.51","DIFF":"-0.26"},
            {"TRADE_DT":"20260803","PRODCD":"B027","PRODNM":"휘발유","PRICE":"1866.69","DIFF":"-0.27"},
            {"TRADE_DT":"20260803","PRODCD":"D047","PRODNM":"자동차용경유","PRICE":"1849.74","DIFF":"-0.31"},
            {"TRADE_DT":"20260803","PRODCD":"C004","PRODNM":"실내등유","PRICE":"1586.35","DIFF":"-0.12"},
            {"TRADE_DT":"20260803","PRODCD":"K015","PRODNM":"자동차용부탄","PRICE":"1104.38","DIFF":"-0.05"}
            ]}}""";

    @Test
    @DisplayName("맨 ObjectMapper로도 TRADE_DT/PRODNM/DIFF 같은 미매핑 필드가 섞인 실응답을 파싱한다")
    void parsesRawResponseWithPlainObjectMapper() throws Exception {
        ObjectMapper plainMapper = new ObjectMapper();

        OpinetPriceResponse response = plainMapper.readValue(RAW_RESPONSE, OpinetPriceResponse.class);

        List<OpinetPriceResponse.Oil> oils = response.result().oil();
        String gasolinePrice = oils.stream()
                .filter(oil -> "B027".equals(oil.prodcd()))
                .findFirst()
                .orElseThrow()
                .price();
        assertThat(gasolinePrice).isEqualTo("1866.69");
    }
}
