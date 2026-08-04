package com.ssafy.ieumgil.domain.festival.client;

import com.ssafy.ieumgil.domain.festival.dto.TourApiDetailResponse;
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

    /** 총건수만 확인한다(1건만 요청). 배치가 돌아야 할 페이지 수를 정하는 데 쓴다. */
    public int fetchTotalCount(String eventStartDate) {
        TourApiResponse.Body body = fetchBody(eventStartDate, 1, 1);
        return body == null || body.totalCount() == null ? 0 : body.totalCount();
    }

    public List<TourApiResponse.Item> searchFestivals(String eventStartDate, int pageNo, int numOfRows) {
        TourApiResponse.Body body = fetchBody(eventStartDate, pageNo, numOfRows);
        if (body == null || body.items() == null || body.items().item() == null) {
            return List.of();
        }
        return body.items().item();
    }

    /**
     * 축제 상세의 homepage 원문을 준다. 없으면 null.
     *
     * <p>searchFestival2(목록)에는 homepage가 없어 detailCommon2(상세)를 따로 부른다.
     * 배치가 축제마다 한 번 부른다(총 209건 규모).
     */
    public String fetchDetailHomepage(String contentId) {
        try {
            String query = "serviceKey=" + URLEncoder.encode(properties.serviceKey(), StandardCharsets.UTF_8)
                    + "&MobileOS=ETC"
                    + "&MobileApp=ieumgil"
                    + "&_type=json"
                    + "&contentId=" + URLEncoder.encode(contentId, StandardCharsets.UTF_8);
            URI uri = URI.create(properties.baseUrl() + "/detailCommon2?" + query);
            TourApiDetailResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(TourApiDetailResponse.class);
            if (response == null || response.response() == null || response.response().body() == null
                    || response.response().body().items() == null
                    || response.response().body().items().item() == null
                    || response.response().body().items().item().isEmpty()) {
                return null;
            }
            return response.response().body().items().item().get(0).homepage();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("투어API 축제 상세 조회 실패: {}", e.getMessage());
            throw new FestivalException(FestivalErrorCode.TOUR_API_CALL_FAILED);
        }
    }

    private TourApiResponse.Body fetchBody(String eventStartDate, int pageNo, int numOfRows) {
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
            if (response == null || response.response() == null) {
                return null;
            }
            return response.response().body();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("투어API 축제 조회 실패: {}", e.getMessage());
            throw new FestivalException(FestivalErrorCode.TOUR_API_CALL_FAILED);
        }
    }
}
