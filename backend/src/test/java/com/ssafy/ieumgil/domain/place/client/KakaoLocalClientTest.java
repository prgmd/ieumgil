package com.ssafy.ieumgil.domain.place.client;

import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoPlaceResponse;
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
}
