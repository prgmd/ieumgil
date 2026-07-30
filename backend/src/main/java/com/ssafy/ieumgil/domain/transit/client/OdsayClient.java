package com.ssafy.ieumgil.domain.transit.client;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class OdsayClient {

    private final RestClient restClient;
    private final OdsayProperties properties;

    public OdsayClient(RestClient.Builder builder, OdsayProperties properties) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    public Optional<OdsayRouteResponse.Path> searchPublicTransitRoute(
            double startLat, double startLng, double endLat, double endLng, String mode) {
        try {
            int searchPathType = "BUS".equals(mode) ? 2 : "SUBWAY".equals(mode) ? 1 : 0;
            URI uri = URI.create(properties.baseUrl() + "/searchPubTransPathT"
                    + "?SX=" + startLng + "&SY=" + startLat
                    + "&EX=" + endLng + "&EY=" + endLat
                    + "&apiKey=" + properties.apiKey()
                    + "&SearchPathType=" + searchPathType);
            OdsayRouteResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayRouteResponse.class);
            if (response != null && response.error() != null && !response.error().isEmpty()) {
                response.error().forEach(error ->
                        log.warn("ODsay 응답 에러: code={}, message={}", error.code(), error.message()));
                throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
            }
            if (response == null || response.result() == null || response.result().path() == null) {
                return Optional.empty();
            }
            List<OdsayRouteResponse.Path> paths = response.result().path();
            return paths.isEmpty() ? Optional.empty() : Optional.of(paths.get(0));
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("ODsay 대중교통 길찾기 실패: {}", e.getMessage());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }
}
