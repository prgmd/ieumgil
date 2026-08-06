package com.ssafy.ieumgil.domain.transit.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.transit.exception.OdsayNoRouteException;
import com.ssafy.ieumgil.domain.transit.exception.OdsayTooCloseException;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ODsay 조회 캐시의 read-through 동작.
 *
 * <p>{@code MockRestServiceServer}가 "정확히 한 번"을 강제하므로, 두 번째 호출이 목 서버로
 * 새면 검증이 실패한다 — 캐시가 실제로 외부 호출을 막았는지를 그 방식으로 확인한다.
 * Redis는 띄우지 않고 맵 기반 대역으로 바꿔 끼운다: 이 테스트가 보려는 것은 Redis 왕복이
 * 아니라 클라이언트가 캐시를 언제 읽고 언제 쓰는지다.
 */
class OdsayClientCacheTest {

    private static final String BASE_URL = "https://api.odsay.com/v1/api";
    private static final String ROUTE_URL = BASE_URL
            + "/searchPubTransPathT?SX=126.9779&SY=37.5663&EX=129.0756&EY=35.1796"
            + "&apiKey=test-key&SearchPathType=0";
    private static final String ROUTE_RESPONSE = """
            { "result": { "path": [ { "pathType": 11, "info": { "totalTime": 138, "totalPayment": 59800 } } ] } }
            """;

    private MockRestServiceServer server;
    private OdsayClient odsayClient;
    private Map<String, String> store;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        store = new HashMap<>();
        odsayClient = new OdsayClient(builder, new OdsayProperties("test-key", BASE_URL), new MapCache(store));
    }

    @Test
    @DisplayName("같은 구간을 두 번 물으면 ODsay를 한 번만 부른다")
    void 같은_구간은_한_번만_부른다() {
        server.expect(once(), requestTo(ROUTE_URL))
                .andRespond(withSuccess(ROUTE_RESPONSE, MediaType.APPLICATION_JSON));

        var first = odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT");
        var second = odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT");

        server.verify();
        // 캐시 히트가 미스와 구분되지 않아야 한다 — 파싱된 값이 그대로 재현된다
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).info().totalPayment()).isEqualTo(59800);
        assertThat(second.get(0).pathType()).isEqualTo(11);
    }

    @Test
    @DisplayName("'경로 없음'도 캐시한다 — 영구적인 답을 매번 물을 이유가 없다")
    void 경로_없음도_캐시한다() {
        server.expect(once(), requestTo(ROUTE_URL))
                .andRespond(withSuccess("""
                        { "error": { "msg": "검색결과가 없습니다.", "code": "-99" } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT"))
                .isInstanceOf(OdsayNoRouteException.class);
        // 두 번째는 목 서버를 타지 않고 캐시가 같은 예외를 재현한다
        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT"))
                .isInstanceOf(OdsayNoRouteException.class);

        server.verify();
    }

    @Test
    @DisplayName("'700m 이내'도 캐시하되 '경로 없음'과 섞이지 않는다")
    void 칠백미터_이내도_캐시하고_구분한다() {
        server.expect(once(), requestTo(ROUTE_URL))
                .andRespond(withSuccess("""
                        { "error": { "msg": "출, 도착지가 700m이내입니다.", "code": "-98" } }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT"))
                .isInstanceOf(OdsayTooCloseException.class);
        // 캐시가 갈래를 잃으면 이 두 번째 단정이 OdsayNoRouteException으로 깨진다 —
        // 그 구분이 사라지면 걸어갈 거리가 "갈 수 없는 곳"이 된다
        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT"))
                .isInstanceOf(OdsayTooCloseException.class)
                .isNotInstanceOf(OdsayNoRouteException.class);

        server.verify();
    }

    @Test
    @DisplayName("진짜 장애는 캐시하지 않는다 — 일시적 실패를 TTL 내내 굳히면 재시도가 무의미해진다")
    void 장애는_캐시하지_않는다() {
        // 429·타임아웃 같은 일시 장애를 캐시하면 재시도가 유효한 유일한 상태를 재시도 불가로 바꾼다
        server.expect(once(), requestTo(ROUTE_URL)).andRespond(withServerError());
        server.expect(once(), requestTo(ROUTE_URL))
                .andRespond(withSuccess(ROUTE_RESPONSE, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() ->
                odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT"))
                .isInstanceOf(TransitException.class);
        assertThat(store).as("장애 응답이 캐시에 남지 않아야 한다").isEmpty();

        // 그래서 두 번째는 실제로 다시 부르고 성공한다 — 장애를 캐시했다면 목 서버의
        // 두 번째 기대가 소비되지 않아 server.verify()가 깨진다
        assertThat(odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT"))
                .hasSize(1);

        server.verify();
        assertThat(store).as("성공한 응답은 캐시된다").hasSize(1);
    }

    @Test
    @DisplayName("시간표도 두 번째부터 캐시로 답한다")
    void 시간표도_한_번만_부른다() {
        String url = BASE_URL + "/trainServiceTime?startStationID=3300128&endStationID=3300108&apiKey=test-key";
        server.expect(once(), requestTo(url))
                .andRespond(withSuccess("""
                        { "result": { "station": [ { "trainClass": "KTX", "trainNo": 1,
                          "departureTime": "16:00", "arrivalTime": "18:37", "runDay": "매일" } ] } }
                        """, MediaType.APPLICATION_JSON));

        var first = odsayClient.getTrainSchedule(3300128, 3300108);
        var second = odsayClient.getTrainSchedule(3300128, 3300108);

        server.verify();
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).departureTime()).isEqualTo("16:00");
        assertThat(second.get(0).trainClass()).isEqualTo("KTX");
    }

    @Test
    @DisplayName("다른 구간은 캐시를 공유하지 않는다")
    void 다른_구간은_따로_부른다() {
        String otherUrl = BASE_URL
                + "/searchPubTransPathT?SX=126.9779&SY=37.5663&EX=126.5312&EY=33.4996"
                + "&apiKey=test-key&SearchPathType=0";
        server.expect(once(), requestTo(ROUTE_URL))
                .andRespond(withSuccess(ROUTE_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(otherUrl))
                .andRespond(withSuccess(ROUTE_RESPONSE, MediaType.APPLICATION_JSON));

        odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 35.1796, 129.0756, "TRANSIT");
        odsayClient.searchPublicTransitRoute(37.5663, 126.9779, 33.4996, 126.5312, "TRANSIT");

        server.verify();
        assertThat(store).hasSize(2);
    }

    /** Redis 대신 맵을 쓰는 캐시. 직렬화 경로는 그대로 태운다 — 그게 깨지면 히트가 미스와 달라진다 */
    private static class MapCache extends TransitApiCache {

        private final Map<String, String> store;
        private final ObjectMapper mapper = new ObjectMapper();

        MapCache(Map<String, String> store) {
            super(null, null);
            this.store = store;
        }

        @Override
        public <T> java.util.Optional<T> read(String key, com.fasterxml.jackson.core.type.TypeReference<T> type) {
            String raw = store.get(key);
            if (raw == null) {
                return java.util.Optional.empty();
            }
            try {
                return java.util.Optional.ofNullable(mapper.readValue(raw, type));
            } catch (Exception e) {
                return java.util.Optional.empty();
            }
        }

        @Override
        public void write(String key, Object value, Duration ttl) {
            try {
                store.put(key, mapper.writeValueAsString(value));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
