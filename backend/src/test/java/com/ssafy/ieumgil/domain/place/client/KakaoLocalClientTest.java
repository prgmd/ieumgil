package com.ssafy.ieumgil.domain.place.client;

import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoDirectionsResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoPlaceResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoWalkingRouteResponse;
import com.ssafy.ieumgil.domain.place.exception.PlaceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(KakaoLocalClient.class)
class KakaoLocalClientTest {

    private static final String KEYWORD_RESPONSE = """
            {
              "documents": [
                {
                  "id": "26338954",
                  "place_name": "성산일출봉",
                  "category_group_name": "관광명소",
                  "address_name": "제주 서귀포시 성산읍 성산리",
                  "road_address_name": "제주 서귀포시 성산읍 일출로 284-12",
                  "x": "126.9425",
                  "y": "33.4581"
                }
              ],
              "meta": { "total_count": 1, "pageable_count": 1, "is_end": true }
            }
            """;

    private static final String ADDRESS_RESPONSE = """
            {
              "documents": [
                {
                  "road_address": { "address_name": "제주 서귀포시 성산읍 일출로 284-12" },
                  "address": { "address_name": "제주 서귀포시 성산읍 성산리" }
                }
              ],
              "meta": { "total_count": 1 }
            }
            """;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestPropertiesConfig {
        @org.springframework.context.annotation.Bean
        KakaoLocalProperties kakaoLocalProperties() {
            return new KakaoLocalProperties("test-key", "https://dapi.kakao.com");
        }
    }

    @Autowired
    private KakaoLocalClient kakaoLocalClient;

    @Autowired
    private MockRestServiceServer server;

    @Test
    void searchByKeywordParsesDocumentsAndSendsAuthHeader() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=%EC%84%B1%EC%82%B0%EC%9D%BC%EC%B6%9C%EB%B4%89&x=126.5&y=33.5"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(KEYWORD_RESPONSE, MediaType.APPLICATION_JSON));

        List<KakaoPlaceResponse.Document> result = kakaoLocalClient.searchByKeyword("성산일출봉", 33.5, 126.5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("26338954");
        assertThat(result.get(0).place_name()).isEqualTo("성산일출봉");
    }

    @Test
    void searchByKeywordWithoutCoordsOmitsXY() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=%EC%B9%B4%ED%8E%98"))
                .andExpect(method(GET))
                .andRespond(withSuccess(KEYWORD_RESPONSE, MediaType.APPLICATION_JSON));

        List<KakaoPlaceResponse.Document> result = kakaoLocalClient.searchByKeyword("카페", null, null);

        assertThat(result).hasSize(1);
    }

    @Test
    void serverErrorThrowsPlaceException() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json?query=%EC%B9%B4%ED%8E%98"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> kakaoLocalClient.searchByKeyword("카페", null, null))
                .isInstanceOf(PlaceException.class);
    }

    @Test
    void coord2AddressReturnsRoadAddressDocument() {
        server.expect(requestTo("https://dapi.kakao.com/v2/local/geo/coord2address.json?x=126.9425&y=33.4581"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(ADDRESS_RESPONSE, MediaType.APPLICATION_JSON));

        Optional<KakaoAddressResponse.Document> result = kakaoLocalClient.coord2Address(33.4581, 126.9425);

        assertThat(result).isPresent();
        assertThat(result.get().road_address().address_name()).isEqualTo("제주 서귀포시 성산읍 일출로 284-12");
    }

    @Test
    void coord2AddressWithNoMatchReturnsEmpty() {
        String emptyResponse = """
                { "documents": [], "meta": { "total_count": 0 } }
                """;
        server.expect(requestTo("https://dapi.kakao.com/v2/local/geo/coord2address.json?x=200.0&y=100.0"))
                .andRespond(withSuccess(emptyResponse, MediaType.APPLICATION_JSON));

        Optional<KakaoAddressResponse.Document> result = kakaoLocalClient.coord2Address(100.0, 200.0);

        assertThat(result).isEmpty();
    }

    @Test
    void getWalkingRouteParsesRouteProperties() {
        String routeResponse = """
                {
                  "status": "OK",
                  "route": {
                    "properties": {
                      "totalDistance": 4025,
                      "totalTime": 3914,
                      "landingUrl": "https://map.kakao.com/route/walk/example"
                    }
                  }
                }
                """;
        server.expect(requestTo("https://dapi.kakao.com/v2/routing/walk?start_x=126.9425&start_y=33.4581&end_x=126.94&end_y=33.46"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(routeResponse, MediaType.APPLICATION_JSON));

        Optional<KakaoWalkingRouteResponse.Properties> result = kakaoLocalClient.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94);

        assertThat(result).isPresent();
        assertThat(result.get().totalDistance()).isEqualTo(4025);
        assertThat(result.get().totalTime()).isEqualTo(3914);
    }

    @Test
    void getWalkingRouteWithNonOkStatusReturnsEmpty() {
        String failedResponse = """
                {
                  "status": "START_LINK_NOT_FOUND",
                  "route": {
                    "legs": [],
                    "properties": { "totalDistance": 0, "totalTime": 0 }
                  }
                }
                """;
        server.expect(requestTo("https://dapi.kakao.com/v2/routing/walk?start_x=126.9425&start_y=33.4581&end_x=126.94&end_y=33.46"))
                .andRespond(withSuccess(failedResponse, MediaType.APPLICATION_JSON));

        Optional<KakaoWalkingRouteResponse.Properties> result = kakaoLocalClient.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94);

        assertThat(result).isEmpty();
    }

    @Test
    void walkingRouteServerErrorThrowsPlaceException() {
        server.expect(requestTo("https://dapi.kakao.com/v2/routing/walk?start_x=126.9425&start_y=33.4581&end_x=126.94&end_y=33.46"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> kakaoLocalClient.getWalkingRoute(33.4581, 126.9425, 33.46, 126.94))
                .isInstanceOf(PlaceException.class);
    }

    @Test
    void getDrivingRouteParsesFareDistanceDuration() {
        String directionsResponse = """
                {
                  "routes": [
                    {
                      "result_code": 0,
                      "summary": {
                        "fare": { "taxi": 13200, "toll": 0 },
                        "distance": 8647,
                        "duration": 1672
                      }
                    }
                  ]
                }
                """;
        server.expect(requestTo("https://apis-navi.kakaomobility.com/v1/directions?origin=127.0246,37.5326&destination=127.0396,37.5013&priority=TIME"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(directionsResponse, MediaType.APPLICATION_JSON));

        Optional<KakaoDirectionsResponse.Summary> result =
                kakaoLocalClient.getDrivingRoute(37.5326, 127.0246, 37.5013, 127.0396);

        assertThat(result).isPresent();
        assertThat(result.get().fare().taxi()).isEqualTo(13200);
        assertThat(result.get().distance()).isEqualTo(8647);
        assertThat(result.get().duration()).isEqualTo(1672);
    }

    @Test
    void getDrivingRouteWithNonZeroResultCodeReturnsEmpty() {
        String failedResponse = """
                {
                  "routes": [
                    { "result_code": 102, "result_msg": "시작 지점 주변의 도로를 탐색할 수 없음" }
                  ]
                }
                """;
        server.expect(requestTo("https://apis-navi.kakaomobility.com/v1/directions?origin=126.9425,33.4581&destination=126.9349,33.4614&priority=TIME"))
                .andRespond(withSuccess(failedResponse, MediaType.APPLICATION_JSON));

        Optional<KakaoDirectionsResponse.Summary> result =
                kakaoLocalClient.getDrivingRoute(33.4581, 126.9425, 33.4614, 126.9349);

        assertThat(result).isEmpty();
    }

    @Test
    void drivingRouteServerErrorThrowsPlaceException() {
        server.expect(requestTo("https://apis-navi.kakaomobility.com/v1/directions?origin=127.0246,37.5326&destination=127.0396,37.5013&priority=TIME"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> kakaoLocalClient.getDrivingRoute(37.5326, 127.0246, 37.5013, 127.0396))
                .isInstanceOf(PlaceException.class);
    }

    @Test
    void searchByKeywordInRectSendsRectAsMinXMinYMaxXMaxY() {
        // 카카오 rect 순서는 minX,minY,maxX,maxY = 남서 경도,남서 위도,북동 경도,북동 위도.
        // 순서가 틀리면 결과가 0건으로 조용히 비므로 조립 순서를 고정한다(라이브로 확인한 형식).
        server.expect(requestTo("https://dapi.kakao.com/v2/local/search/keyword.json"
                        + "?query=%EC%B9%B4%ED%8E%98&rect=126.93,33.44,126.95,33.47"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(KEYWORD_RESPONSE, MediaType.APPLICATION_JSON));

        List<KakaoPlaceResponse.Document> result = kakaoLocalClient.searchByKeywordInRect(
                "카페", 33.44, 126.93, 33.47, 126.95);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("26338954");
    }
}
