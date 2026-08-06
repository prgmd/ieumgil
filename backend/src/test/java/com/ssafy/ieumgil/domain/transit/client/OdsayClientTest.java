package com.ssafy.ieumgil.domain.transit.client;

import com.ssafy.ieumgil.domain.transit.dto.OdsayBusScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayBusTerminalResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayFlightScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainTerminalResponse;
import com.ssafy.ieumgil.domain.transit.exception.OdsayNoRouteException;
import com.ssafy.ieumgil.domain.transit.exception.OdsayTooCloseException;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(OdsayClient.class)
@Import(OdsayClientTest.NoCacheConfig.class)
class OdsayClientTest {

    /**
     * 이 테스트는 ODsay 응답 파싱과 에러 분류를 본다 — 캐시는 검증 대상이 아니고, 히트가 나면
     * 목 서버 호출 자체가 일어나지 않아 검증이 무력해진다. 그래서 비활성 캐시를 넣는다.
     */
    @TestConfiguration
    static class NoCacheConfig {

        @Bean
        TransitApiCache transitApiCache() {
            return TransitApiCache.disabled();
        }
    }

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

    private static final String TRAIN_TERMINAL_RESPONSE = """
            {
              "result": [
                {
                  "stationID": 3300125,
                  "stationName": "서경주",
                  "x": 129.2035,
                  "y": 35.7642,
                  "haveDestinationTerminals": true,
                  "arrivalTerminals": [
                    {"stationID": 3300108, "stationName": "부산", "x": 129.0422, "y": 35.1152}
                  ]
                },
                {
                  "stationID": 3300128,
                  "stationName": "서울",
                  "x": 126.9706,
                  "y": 37.5545,
                  "haveDestinationTerminals": true,
                  "arrivalTerminals": [
                    {"stationID": 3300108, "stationName": "부산", "x": 129.0422, "y": 35.1152},
                    {"stationID": 3300062, "stationName": "대구", "x": 128.5960, "y": 35.8760}
                  ]
                }
              ]
            }
            """;

    private static final String BUS_TERMINAL_RESPONSE = """
            {
              "result": [
                {
                  "stationID": 4000022,
                  "stationName": "서울남부터미널",
                  "x": 127.0156,
                  "y": 37.4845,
                  "haveDestinationTerminals": false,
                  "destinationTerminals": []
                },
                {
                  "stationID": 4000057,
                  "stationName": "서울고속버스터미널",
                  "x": 127.0058,
                  "y": 37.5057,
                  "haveDestinationTerminals": true,
                  "destinationTerminals": [
                    {"stationID": 4000156, "stationName": "부산종합버스터미널", "x": 129.0954, "y": 35.2847}
                  ]
                }
              ]
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

        List<OdsayRouteResponse.Path> result =
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS");

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).info().totalTime()).isEqualTo(42);
        assertThat(result.get(0).info().payment()).isEqualTo(1400);
        assertThat(result.get(0).info().totalIntervalTime()).isEqualTo(13);
    }

    @Test
    void subwayModeUsesSearchPathTypeOne() {
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(ROUTE_RESPONSE, MediaType.APPLICATION_JSON));

        List<OdsayRouteResponse.Path> result =
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "SUBWAY");

        assertThat(result).isNotEmpty();
    }

    @Test
    void emptyPathListReturnsEmpty() {
        String emptyResponse = """
                { "result": { "path": [] } }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=200.0&SY=100.0&EX=200.1&EY=100.1&apiKey=test-key&SearchPathType=2"))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        List<OdsayRouteResponse.Path> result =
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
    @DisplayName("[실측] 경로 없음(code -99)은 장애가 아니라 '경로가 없다'로 구분한다")
    void 경로_없음_에러는_경로없음_예외다() {
        // 실측: 울릉도 목적지. {"error":{"msg":"검색결과가 없습니다.","code":"-99"}}
        String errorResponse = """
                { "error": { "msg": "검색결과가 없습니다.", "code": "-99" } }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=2"))
                .andRespond(withSuccess(errorResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .isInstanceOf(OdsayNoRouteException.class);
    }

    @Test
    @DisplayName("[실측] 정류장 없음(code 3)도 '경로가 없다'다 — 재시도해도 답이 같다")
    void 정류장_없음_에러도_경로없음_예외다() {
        // 실측: 백령도 목적지. {"error":{"msg":"출발지 정류장이 없습니다.","code":"3"}}
        String errorResponse = """
                { "error": { "msg": "출발지 정류장이 없습니다.", "code": "3" } }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=2"))
                .andRespond(withSuccess(errorResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .isInstanceOf(OdsayNoRouteException.class);
    }

    @Test
    @DisplayName("[실측] 700m 이내(code -98)는 '경로 없음'이 아니다 — 걸어갈 거리라는 뜻이다")
    void 칠백미터_이내는_경로없음이_아니다() {
        // 실측: 속초시외버스터미널 → 속초시청. {"error":{"msg":"출, 도착지가 700m이내입니다.","code":"-98"}}
        // 이 둘을 뭉갰다가 서울→속초에서 고속버스 후보가 통째로 사라졌다 — 하차 지점이 목적지에서
        // 700m 이내라 이탈 조회가 실패로 취급됐고, 목적지가 터미널 근처인 최선의 경우가 수단을 지웠다.
        String errorResponse = """
                { "error": { "msg": "출, 도착지가 700m이내입니다.", "code": "-98" } }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=2"))
                .andRespond(withSuccess(errorResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .isInstanceOf(OdsayTooCloseException.class)
                .isNotInstanceOf(OdsayNoRouteException.class);
    }

    @Test
    @DisplayName("인증 실패(code 500)는 '경로 없음'이 아니다 — 장애를 영구적인 답으로 위장하면 안 된다")
    void 인증_실패는_경로없음_예외가_아니다() {
        String errorResponse = """
                { "error": [ { "code": "500", "message": "[ApiKeyAuthFailed] ApiKey authentication failed." } ] }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=2"))
                .andRespond(withSuccess(errorResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .isInstanceOf(TransitException.class)
                .isNotInstanceOf(OdsayNoRouteException.class);
    }

    @Test
    void serverErrorThrowsTransitException() {
        server.expect(requestTo("https://api.odsay.com/v1/api/searchPubTransPathT?SX=127.0276&SY=37.4979&EX=127.1054&EY=37.5665&apiKey=test-key&SearchPathType=2"))
                .andRespond(withServerError());

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.4979, 127.0276, 37.5665, 127.1054, "BUS"))
                .isInstanceOf(TransitException.class);
    }

    @Test
    void searchTrainTerminalPicksExactNameMatchOverFirstResult() {
        server.expect(requestTo("https://api.odsay.com/v1/api/trainTerminals?terminalName=%EC%84%9C%EC%9A%B8&apiKey=test-key"))
                .andExpect(method(GET))
                .andRespond(withSuccess(TRAIN_TERMINAL_RESPONSE, MediaType.APPLICATION_JSON));

        Optional<OdsayTrainTerminalResponse.Terminal> result = odsayClient.searchTrainTerminal("서울");

        assertThat(result).isPresent();
        assertThat(result.get().stationID()).isEqualTo(3300128);
        assertThat(result.get().stationName()).isEqualTo("서울");
        assertThat(result.get().arrivalTerminals()).hasSize(2);
    }

    @Test
    void searchTrainTerminalWithNoMatchReturnsEmpty() {
        String emptyResponse = """
                { "result": [] }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/trainTerminals?terminalName=%EC%A1%B4%EC%9E%AC%EC%95%88%ED%95%A8&apiKey=test-key"))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        Optional<OdsayTrainTerminalResponse.Terminal> result = odsayClient.searchTrainTerminal("존재안함");

        assertThat(result).isEmpty();
    }

    @Test
    void searchExpressBusTerminalPicksEntryWithDestinationsOverFirstResult() {
        server.expect(requestTo("https://api.odsay.com/v1/api/expressBusTerminals?terminalName=%EC%84%9C%EC%9A%B8&apiKey=test-key"))
                .andExpect(method(GET))
                .andRespond(withSuccess(BUS_TERMINAL_RESPONSE, MediaType.APPLICATION_JSON));

        Optional<OdsayBusTerminalResponse.Terminal> result = odsayClient.searchExpressBusTerminal("서울");

        assertThat(result).isPresent();
        assertThat(result.get().stationID()).isEqualTo(4000057);
        assertThat(result.get().stationName()).isEqualTo("서울고속버스터미널");
        assertThat(result.get().destinationTerminals()).hasSize(1);
    }

    @Test
    void searchIntercityBusTerminalPicksEntryWithDestinationsOverFirstResult() {
        server.expect(requestTo("https://api.odsay.com/v1/api/intercityBusTerminals?terminalName=%EC%84%9C%EC%9A%B8&apiKey=test-key"))
                .andExpect(method(GET))
                .andRespond(withSuccess(BUS_TERMINAL_RESPONSE, MediaType.APPLICATION_JSON));

        Optional<OdsayBusTerminalResponse.Terminal> result = odsayClient.searchIntercityBusTerminal("서울");

        assertThat(result).isPresent();
        assertThat(result.get().stationID()).isEqualTo(4000057);
    }

    @Test
    void terminalSearchErrorEnvelopeThrowsTransitException() {
        String errorResponse = """
                { "error": [{"code": "500", "message": "[ApiKeyAuthFailed] ApiKey authentication failed."}] }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/trainTerminals?terminalName=%EC%84%9C%EC%9A%B8&apiKey=test-key"))
                .andRespond(withSuccess(errorResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> odsayClient.searchTrainTerminal("서울"))
                .isInstanceOf(TransitException.class);
    }

    private static final String TRAIN_SCHEDULE_RESPONSE = """
            {
              "result": {
                "count": 89,
                "startStationName": "서울",
                "endStationName": "부산",
                "station": [
                  {
                    "railName": "KTX경부선",
                    "trainClass": "KTX",
                    "trainNo": 1,
                    "departureTime": "05:13",
                    "arrivalTime": "07:50",
                    "wasteTime": "02:37",
                    "runDay": "매일",
                    "fare": {"general": "59800", "special": "83700", "standing": "50830"}
                  }
                ]
              }
            }
            """;

    @Test
    void getTrainScheduleParsesFirstDeparture() {
        server.expect(requestTo("https://api.odsay.com/v1/api/trainServiceTime?startStationID=3300128&endStationID=3300108&apiKey=test-key"))
                .andExpect(method(GET))
                .andRespond(withSuccess(TRAIN_SCHEDULE_RESPONSE, MediaType.APPLICATION_JSON));

        List<OdsayTrainScheduleResponse.Train> result = odsayClient.getTrainSchedule(3300128, 3300108);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).departureTime()).isEqualTo("05:13");
        assertThat(result.get(0).fare().general()).isEqualTo(59800);
    }

    @Test
    void getTrainScheduleWithNoServiceReturnsEmpty() {
        String emptyResponse = """
                { "result": { "count": 0, "station": [] } }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/trainServiceTime?startStationID=1&endStationID=2&apiKey=test-key"))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        List<OdsayTrainScheduleResponse.Train> result = odsayClient.getTrainSchedule(1, 2);

        assertThat(result).isEmpty();
    }

    @Test
    void getTrainScheduleParsesEmptyFareAndDayFareBreakdown() {
        String srtResponse = """
                {
                  "result": {
                    "count": 1,
                    "station": [
                      {
                        "railName": "경부선",
                        "trainClass": "SRT",
                        "trainNo": 305,
                        "departureTime": "07:30",
                        "arrivalTime": "09:47",
                        "wasteTime": "02:17",
                        "runDay": "매일",
                        "fare": {},
                        "generalFare": {"weekend": "22900", "holiday": "23100"},
                        "specialFare": {"weekend": "33300", "holiday": "33500"},
                        "standingFare": {"weekend": "19400", "holiday": "19600"}
                      }
                    ]
                  }
                }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/trainServiceTime?startStationID=3300128&endStationID=3300108&apiKey=test-key"))
                .andRespond(withSuccess(srtResponse, MediaType.APPLICATION_JSON));

        List<OdsayTrainScheduleResponse.Train> result = odsayClient.getTrainSchedule(3300128, 3300108);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fare().general()).isNull();
        assertThat(result.get(0).generalFare().weekend()).isEqualTo(22900);
        assertThat(result.get(0).generalFare().weekday()).isNull();
    }

    private static final String BUS_SCHEDULE_RESPONSE = """
            {
              "result": {
                "count": 51,
                "stationClass": 4,
                "startStationName": "서울고속버스터미널",
                "endStationName": "부산종합버스터미널",
                "firstTime": "06:00",
                "lastTime": "26:00",
                "schedule": [
                  {"busClass": 2, "departureTime": "06:00", "wasteTime": 240, "fare": 39700}
                ]
              }
            }
            """;

    @Test
    void getIntercityBusScheduleParsesFirstDeparture() {
        server.expect(requestTo("https://api.odsay.com/v1/api/searchInterBusSchedule?startStationID=4000057&endStationID=4000156&apiKey=test-key"))
                .andExpect(method(GET))
                .andRespond(withSuccess(BUS_SCHEDULE_RESPONSE, MediaType.APPLICATION_JSON));

        List<OdsayBusScheduleResponse.Bus> result = odsayClient.getIntercityBusSchedule(4000057, 4000156);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fare()).isEqualTo(39700);
        assertThat(result.get(0).wasteTime()).isEqualTo(240);
    }

    @Test
    void getIntercityBusScheduleWithNoServiceReturnsEmpty() {
        String emptyResponse = """
                { "result": { "count": 0, "schedule": [] } }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/searchInterBusSchedule?startStationID=1&endStationID=2&apiKey=test-key"))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        List<OdsayBusScheduleResponse.Bus> result = odsayClient.getIntercityBusSchedule(1, 2);

        assertThat(result).isEmpty();
    }

    private static final String FLIGHT_SCHEDULE_RESPONSE = """
            {
              "result": {
                "count": 97,
                "startStationName": "김포국제공항",
                "endStationName": "제주",
                "station": [
                  {"airline": "에어서울", "departureTime": "06:00", "arrivalTime": "07:15", "flight": "RS901", "runDay": "매일"}
                ]
              }
            }
            """;

    @Test
    void getFlightScheduleParsesFirstDeparture() {
        server.expect(requestTo("https://api.odsay.com/v1/api/airServiceTime?startStationID=3500001&endStationID=3500003&apiKey=test-key"))
                .andExpect(method(GET))
                .andRespond(withSuccess(FLIGHT_SCHEDULE_RESPONSE, MediaType.APPLICATION_JSON));

        List<OdsayFlightScheduleResponse.Flight> result = odsayClient.getFlightSchedule(
                DomesticAirport.GIMPO.stationId(), DomesticAirport.JEJU.stationId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).airline()).isEqualTo("에어서울");
        assertThat(result.get(0).flight()).isEqualTo("RS901");
    }

    @Test
    void getFlightScheduleWithNoServiceReturnsEmpty() {
        String emptyResponse = """
                { "result": { "count": 0, "station": [] } }
                """;
        server.expect(requestTo("https://api.odsay.com/v1/api/airServiceTime?startStationID=1&endStationID=2&apiKey=test-key"))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        List<OdsayFlightScheduleResponse.Flight> result = odsayClient.getFlightSchedule(1, 2);

        assertThat(result).isEmpty();
    }
}
