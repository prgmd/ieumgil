package com.ssafy.ieumgil.domain.place.client;

import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
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

    private <T> T callKakao(URI uri, Class<T> type) {
        return restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + properties.restApiKey())
                .retrieve()
                .body(type);
    }
}
