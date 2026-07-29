package com.ssafy.ieumgil.domain.festival.client;

import com.ssafy.ieumgil.domain.festival.dto.TourApiResponse;
import com.ssafy.ieumgil.domain.festival.exception.FestivalErrorCode;
import com.ssafy.ieumgil.domain.festival.exception.FestivalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class TourApiClient {

    private final RestClient restClient;
    private final TourApiProperties properties;

    public TourApiClient(RestClient.Builder builder, TourApiProperties properties) {
        this.properties = properties;
        // searchFestivals가 항상 절대 URI를 넘기므로 baseUrl은 쓰이지 않는다.
        this.restClient = builder.build();
    }

    public List<TourApiResponse.Item> searchFestivals(String eventStartDate, int pageNo, int numOfRows) {
        try {
            // RestClient의 기본 UriBuilder는 쿼리 파라미터 값 안의 '/'를 인코딩하지 않는다 —
            // serviceKey(디코딩된 원본)에 '/'가 섞여 있으면 그대로 나가서 data.go.kr이 401을 낸다.
            // URLEncoder로 직접 인코딩한 뒤 이미 인코딩된 URI로 넘겨 RestClient의 재인코딩을 우회한다.
            String query = "serviceKey=" + URLEncoder.encode(properties.serviceKey(), StandardCharsets.UTF_8)
                    + "&MobileOS=ETC"
                    + "&MobileApp=ieumgil"
                    + "&_type=json"
                    + "&arrange=A"
                    + "&eventStartDate=" + URLEncoder.encode(eventStartDate, StandardCharsets.UTF_8)
                    + "&numOfRows=" + numOfRows
                    + "&pageNo=" + pageNo;
            URI uri = URI.create(properties.baseUrl() + "/searchFestival2?" + query);
            TourApiResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(TourApiResponse.class);
            if (response == null) {
                return List.of();
            }
            var responseWrapper = response.response();
            if (responseWrapper == null) {
                return List.of();
            }
            var body = responseWrapper.body();
            if (body == null) {
                return List.of();
            }
            var items = body.items();
            if (items == null) {
                return List.of();
            }
            return items.item();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("투어API 축제 조회 실패: {}", e.getMessage());
            throw new FestivalException(FestivalErrorCode.TOUR_API_CALL_FAILED);
        }
    }
}
