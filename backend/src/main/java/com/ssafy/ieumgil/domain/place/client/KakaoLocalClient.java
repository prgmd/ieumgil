package com.ssafy.ieumgil.domain.place.client;

import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoDirectionsResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoGeocodeResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoPlaceResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoWalkingRouteResponse;
import com.ssafy.ieumgil.domain.place.exception.PlaceErrorCode;
import com.ssafy.ieumgil.domain.place.exception.PlaceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class KakaoLocalClient {

    private static final String DIRECTIONS_BASE_URL = "https://apis-navi.kakaomobility.com";

    private final RestClient restClient;
    private final KakaoLocalProperties properties;

    public KakaoLocalClient(RestClient.Builder builder, KakaoLocalProperties properties) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    public List<KakaoPlaceResponse.Document> searchByKeyword(String query, Double lat, Double lng) {
        try {
            StringBuilder qs = new StringBuilder("query=")
                    .append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            if (lat != null && lng != null) {
                qs.append("&x=").append(lng).append("&y=").append(lat);
            }
            URI uri = URI.create(properties.baseUrl() + "/v2/local/search/keyword.json?" + qs);
            KakaoPlaceResponse response = callKakao(uri, KakaoPlaceResponse.class);
            if (response == null || response.documents() == null) {
                return List.of();
            }
            return response.documents();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("카카오 로컬 검색 실패: {}", e.getMessage());
            throw new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED);
        }
    }

    /**
     * 사각 범위 안에서만 검색한다 (챗봇 지도 기반 추천 모드).
     *
     * <p>{@code searchByKeyword}의 x/y는 거리순 정렬 힌트일 뿐 범위를 제한하지 않는다.
     * rect는 실제로 결과를 범위 안으로 한정한다 — 라이브 확인 결과 같은 질의가
     * 제한 없이는 143,083건(전국 산개), 좁은 rect로는 23건(전부 박스 안)이었다.
     *
     * <p>파라미터 순서는 {@code minX,minY,maxX,maxY} = 남서 경도, 남서 위도, 북동 경도,
     * 북동 위도다. 순서가 틀리면 예외 없이 0건으로 조용히 비므로 주의한다.
     */
    public List<KakaoPlaceResponse.Document> searchByKeywordInRect(
            String query, double swLat, double swLng, double neLat, double neLng) {
        try {
            String qs = "query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&rect=" + swLng + "," + swLat + "," + neLng + "," + neLat;
            URI uri = URI.create(properties.baseUrl() + "/v2/local/search/keyword.json?" + qs);
            KakaoPlaceResponse response = callKakao(uri, KakaoPlaceResponse.class);
            if (response == null || response.documents() == null) {
                return List.of();
            }
            return response.documents();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("카카오 로컬 범위 검색 실패: {}", e.getMessage());
            throw new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED);
        }
    }

    public Optional<KakaoAddressResponse.Document> coord2Address(double lat, double lng) {
        try {
            URI uri = URI.create(properties.baseUrl() + "/v2/local/geo/coord2address.json?x=" + lng + "&y=" + lat);
            KakaoAddressResponse response = callKakao(uri, KakaoAddressResponse.class);
            if (response == null || response.documents() == null || response.documents().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(response.documents().get(0));
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("카카오 좌표→주소 변환 실패: {}", e.getMessage());
            throw new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED);
        }
    }

    /** 주소 → 좌표(정지오코딩). {@code coord2Address}의 반대 방향이다 */
    public Optional<KakaoGeocodeResponse.Document> addressSearch(String address) {
        try {
            URI uri = URI.create(properties.baseUrl() + "/v2/local/search/address.json"
                    + "?query=" + URLEncoder.encode(address, StandardCharsets.UTF_8));
            KakaoGeocodeResponse response = callKakao(uri, KakaoGeocodeResponse.class);
            if (response == null || response.documents() == null || response.documents().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(response.documents().get(0));
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("카카오 주소→좌표 변환 실패: {}", e.getMessage());
            throw new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED);
        }
    }

    public Optional<KakaoWalkingRouteResponse.Properties> getWalkingRoute(
            double startLat, double startLng, double endLat, double endLng) {
        try {
            URI uri = URI.create(properties.baseUrl() + "/v2/routing/walk"
                    + "?start_x=" + startLng + "&start_y=" + startLat
                    + "&end_x=" + endLng + "&end_y=" + endLat);
            KakaoWalkingRouteResponse response = callKakao(uri, KakaoWalkingRouteResponse.class);
            if (response == null || response.route() == null || !"OK".equals(response.status())) {
                return Optional.empty();
            }
            return Optional.of(response.route().properties());
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("카카오 도보 길찾기 실패: {}", e.getMessage());
            throw new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED);
        }
    }

    public Optional<KakaoDirectionsResponse.Summary> getDrivingRoute(
            double startLat, double startLng, double endLat, double endLng) {
        try {
            URI uri = URI.create(DIRECTIONS_BASE_URL + "/v1/directions"
                    + "?origin=" + startLng + "," + startLat
                    + "&destination=" + endLng + "," + endLat
                    + "&priority=TIME");
            KakaoDirectionsResponse response = callKakao(uri, KakaoDirectionsResponse.class);
            if (response == null || response.routes() == null || response.routes().isEmpty()) {
                return Optional.empty();
            }
            KakaoDirectionsResponse.Route route = response.routes().get(0);
            if (route.result_code() != 0 || route.summary() == null) {
                return Optional.empty();
            }
            return Optional.of(route.summary());
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("카카오모빌리티 자동차 길찾기 실패: {}", e.getMessage());
            throw new PlaceException(PlaceErrorCode.KAKAO_API_CALL_FAILED);
        }
    }

    private <T> T callKakao(URI uri, Class<T> type) {
        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.restApiKey())
                .retrieve()
                .body(type);
    }
}
