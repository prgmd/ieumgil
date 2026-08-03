package com.ssafy.ieumgil.domain.transit.client;

import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(OpinetClient.class)
class OpinetClientTest {

    private static final String REQUEST_URL =
            "https://www.opinet.co.kr/api/avgAllPrice.do?out=json&code=test-key";

    /**
     * 2026-08-03 실응답을 그대로 옮긴 fixture. 두 가지가 의도적이다.
     * 첫 항목이 고급휘발유(B034, 2341원)이고 휘발유(B027)는 두 번째다 — 순서를 믿으면 25% 비싼 값을 쓴다.
     * PRICE는 소수점이 붙은 문자열이다.
     */
    private static final String SAMPLE_RESPONSE = """


            {"RESULT":
            {"OIL":[
            {"TRADE_DT":"20260803","PRODCD":"B034","PRODNM":"고급휘발유","PRICE":"2341.51","DIFF":"-0.26"},
            {"TRADE_DT":"20260803","PRODCD":"B027","PRODNM":"휘발유","PRICE":"1866.69","DIFF":"-0.27"},
            {"TRADE_DT":"20260803","PRODCD":"D047","PRODNM":"자동차용경유","PRICE":"1849.74","DIFF":"-0.31"},
            {"TRADE_DT":"20260803","PRODCD":"C004","PRODNM":"실내등유","PRICE":"1586.35","DIFF":"-0.12"},
            {"TRADE_DT":"20260803","PRODCD":"K015","PRODNM":"자동차용부탄","PRICE":"1104.38","DIFF":"0.00"}
            ]} }
            """;

    @TestConfiguration
    static class TestPropertiesConfig {
        @Bean
        OpinetProperties opinetProperties() {
            return new OpinetProperties("test-key", "https://www.opinet.co.kr/api");
        }
    }

    @Autowired
    private OpinetClient opinetClient;

    @Autowired
    private MockRestServiceServer server;

    @Test
    @DisplayName("배열 첫 항목이 아니라 PRODCD=B027(휘발유)을 골라 소수점 문자열을 반올림한다")
    void picksGasolineByProductCodeAndRoundsDecimalPrice() {
        // 오피넷은 JSON을 text/html로 내려준다 — 실제 Content-Type을 그대로 재현한다.
        server.expect(requestTo(REQUEST_URL))
                .andExpect(method(GET))
                .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.TEXT_HTML));

        Optional<Integer> price = opinetClient.fetchAverageGasolinePrice();

        assertThat(price).contains(1867);
        // 첫 항목(고급휘발유 2341.51)을 집었다면 2342가 나온다 — 그 사고를 명시적으로 막는다.
        assertThat(price).isNotEqualTo(Optional.of(2342));
    }

    @Test
    @DisplayName("잘못된 키의 빈 배열 응답(HTTP 200)은 실패로 취급한다")
    void emptyOilArrayReturnsEmpty() {
        // 실측: 잘못된 키도 200 + 빈 배열이라 상태코드로는 실패를 알 수 없다.
        String emptyResponse = """


                {"RESULT":
                {"OIL":[

                ]} }
                """;
        server.expect(requestTo(REQUEST_URL))
                .andRespond(withSuccess(emptyResponse, MediaType.TEXT_HTML));

        assertThat(opinetClient.fetchAverageGasolinePrice()).isEmpty();
    }

    @Test
    @DisplayName("휘발유(B027) 항목이 없으면 다른 유종으로 대체하지 않고 실패로 둔다")
    void missingGasolineProductCodeReturnsEmpty() {
        String withoutGasoline = """
                {"RESULT":{"OIL":[
                {"TRADE_DT":"20260803","PRODCD":"B034","PRODNM":"고급휘발유","PRICE":"2341.51","DIFF":"-0.26"},
                {"TRADE_DT":"20260803","PRODCD":"D047","PRODNM":"자동차용경유","PRICE":"1849.74","DIFF":"-0.31"}
                ]}}
                """;
        server.expect(requestTo(REQUEST_URL))
                .andRespond(withSuccess(withoutGasoline, MediaType.TEXT_HTML));

        assertThat(opinetClient.fetchAverageGasolinePrice()).isEmpty();
    }

    @Test
    @DisplayName("JSON이 아닌 응답(점검 안내 페이지 등)은 예외로 끝낸다")
    void nonJsonResponseThrowsTransitException() {
        server.expect(requestTo(REQUEST_URL))
                .andRespond(withSuccess("<html><body>시스템 점검중</body></html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> opinetClient.fetchAverageGasolinePrice())
                .isInstanceOf(TransitException.class);
    }

    @Test
    void serverErrorThrowsTransitException() {
        server.expect(requestTo(REQUEST_URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> opinetClient.fetchAverageGasolinePrice())
                .isInstanceOf(TransitException.class);
    }
}
