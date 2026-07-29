package com.ssafy.ieumgil.domain.festival.client;

import com.ssafy.ieumgil.domain.festival.dto.TourApiResponse;
import com.ssafy.ieumgil.domain.festival.exception.FestivalException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.client.RestClientTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

@RestClientTest(TourApiClient.class)
class TourApiClientTest {

    private static final String SAMPLE_RESPONSE = """
            {
              "response": {
                "header": { "resultCode": "0000", "resultMsg": "OK" },
                "body": {
                  "items": {
                    "item": [
                      {
                        "contentid": "4090201",
                        "title": "가든 나이트 마켓",
                        "addr1": "울산광역시 남구 대공원로 94",
                        "addr2": "",
                        "mapx": "129.2938457635",
                        "mapy": "35.5310582726",
                        "eventstartdate": "20260729",
                        "eventenddate": "20260829",
                        "firstimage": "https://tong.visitkorea.or.kr/cms/resource/02/4090202_image2_1.jpg",
                        "lDongRegnCd": "31",
                        "lDongSignguCd": "140",
                        "lclsSystm2": "EV03"
                      }
                    ]
                  },
                  "numOfRows": 1, "pageNo": 1, "totalCount": 1
                }
              }
            }
            """;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestPropertiesConfig {
        @org.springframework.context.annotation.Bean
        TourApiProperties tourApiProperties() {
            return new TourApiProperties("test-key", "http://apis.data.go.kr/B551011/KorService2");
        }
    }

    @Autowired
    private TourApiClient tourApiClient;

    @Autowired
    private MockRestServiceServer server;

    @Test
    void searchFestivalsParsesItemsFromResponse() {
        server.expect(requestToUriTemplate(
                        "http://apis.data.go.kr/B551011/KorService2/searchFestival2?serviceKey={key}&MobileOS=ETC&MobileApp=ieumgil&_type=json&arrange=A&eventStartDate={date}&numOfRows={rows}&pageNo={page}",
                        "test-key", "20260801", 100, 1))
                .andExpect(method(GET))
                .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

        List<TourApiResponse.Item> items = tourApiClient.searchFestivals("20260801", 1, 100);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).contentid()).isEqualTo("4090201");
        assertThat(items.get(0).title()).isEqualTo("가든 나이트 마켓");
    }

    @Test
    void serverErrorThrowsFestivalException() {
        server.expect(requestToUriTemplate(
                        "http://apis.data.go.kr/B551011/KorService2/searchFestival2?serviceKey={key}&MobileOS=ETC&MobileApp=ieumgil&_type=json&arrange=A&eventStartDate={date}&numOfRows={rows}&pageNo={page}",
                        "test-key", "20260801", 100, 1))
                .andRespond(withServerError());

        assertThatThrownBy(() -> tourApiClient.searchFestivals("20260801", 1, 100))
                .isInstanceOf(FestivalException.class);
    }

    @Test
    void malformedResponseWithMissingBodyReturnsEmptyList() {
        String headerOnlyResponse = """
                {
                  "response": {
                    "header": { "resultCode": "0000", "resultMsg": "OK" }
                  }
                }
                """;
        server.expect(requestToUriTemplate(
                        "http://apis.data.go.kr/B551011/KorService2/searchFestival2?serviceKey={key}&MobileOS=ETC&MobileApp=ieumgil&_type=json&arrange=A&eventStartDate={date}&numOfRows={rows}&pageNo={page}",
                        "test-key", "20260801", 100, 1))
                .andRespond(withSuccess(headerOnlyResponse, MediaType.APPLICATION_JSON));

        List<TourApiResponse.Item> items = tourApiClient.searchFestivals("20260801", 1, 100);

        assertThat(items).isEmpty();
    }

    @Test
    void serviceKeyWithSlashPlusEqualsIsFullyPercentEncoded() {
        // data.go.kr "Decoding" 키는 '/', '+', '=' 같은 문자를 그대로 포함한다.
        // Spring의 기본 UriBuilder는 쿼리 값 안의 '/'를 인코딩하지 않아 401이 났던 회귀 방지 테스트.
        // 공유 TestPropertiesConfig("test-key")는 특수문자가 없어 이 버그를 못 잡으므로
        // 별도의 RestClient.Builder + MockRestServiceServer로 직접 구성한다.
        TourApiProperties properties = new TourApiProperties(
                "ab/cd+ef==", "http://apis.data.go.kr/B551011/KorService2");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        TourApiClient client = new TourApiClient(builder, properties);

        mockServer.expect(requestTo("http://apis.data.go.kr/B551011/KorService2/searchFestival2"
                        + "?serviceKey=ab%2Fcd%2Bef%3D%3D&MobileOS=ETC&MobileApp=ieumgil"
                        + "&_type=json&arrange=A&eventStartDate=20260801&numOfRows=100&pageNo=1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(SAMPLE_RESPONSE, MediaType.APPLICATION_JSON));

        List<TourApiResponse.Item> items = client.searchFestivals("20260801", 1, 100);

        assertThat(items).hasSize(1);
    }
}
