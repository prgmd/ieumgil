package com.ssafy.ieumgil.domain.transit.client;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
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

@RestClientTest(OdsayClient.class)
class OdsayClientTest {

    private static final String ROUTE_RESPONSE = """
            {
              "result": {
                "path": [
                  {
                    "pathType": 2,
                    "info": {
                      "totalTime": 42,
                      "payment": 1400,
                      "busTransitCount": 1,
                      "subwayTransitCount": 0,
                      "totalDistance": 12500.0,
                      "totalIntervalTime": 13
                    }
                  }
                ]
              }
            }
            """;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestPropertiesConfig {
        @org.springframework.context.annotation.Bean
        OdsayProperties odsayProperties() {
            return new OdsayProperties("test-key", "https://api.odsay.com/v1/api");
        }
    }

    @Autowired
    private OdsayClient odsayClient;

    @Autowired
    private MockRestServiceServer server;

    @Test
    void searchPublicTransitRouteParsesFirstPath() {
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=2"))
                .andExpect(method(GET))
                .andRespond(withSuccess(ROUTE_RESPONSE, MediaType.APPLICATION_JSON));

        Optional<OdsayRouteResponse.Path> result =
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS");

        assertThat(result).isPresent();
        assertThat(result.get().info().totalTime()).isEqualTo(42);
        assertThat(result.get().info().payment()).isEqualTo(1400);
        assertThat(result.get().info().totalIntervalTime()).isEqualTo(13);
    }

    @Test
    void subwayModeUsesSearchPathTypeOne() {
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(ROUTE_RESPONSE, MediaType.APPLICATION_JSON));

        Optional<OdsayRouteResponse.Path> result =
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "SUBWAY");

        assertThat(result).isPresent();
    }

    @Test
    void emptyPathListReturnsEmpty() {
        String emptyResponse = """
                { "result": { "path": [] } }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=200.0&SY=100.0&EX=200.1&EY=100.1&apiKey=test-key&SearchPathType=2"))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        Optional<OdsayRouteResponse.Path> result =
                odsayClient.searchPublicTransitRoute(100.0, 200.0, 100.1, 200.1, "BUS");

        assertThat(result).isEmpty();
    }

    @Test
    void apiKeyAuthFailedErrorEnvelopeThrowsTransitException() {
        String errorResponse = """
                { "error": [ { "code": "500", "message": "[ApiKeyAuthFailed] ApiKey authentication failed." } ] }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=2"))
                .andRespond(withSuccess(errorResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .isInstanceOf(TransitException.class);
    }

    @Test
    void serverErrorThrowsTransitException() {
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=2"))
                .andRespond(withServerError());

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .isInstanceOf(TransitException.class);
    }
}
