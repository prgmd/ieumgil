package com.ssafy.ieumgil.domain.transit.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ssafy.ieumgil.domain.transit.dto.OpinetPriceResponse;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * 오피넷(한국석유공사) 전국 평균 유가 조회.
 *
 * <p>타임아웃은 {@code spring.http.client}(5s/15s)를 그대로 쓴다 — 자동 설정된
 * {@code RestClient.Builder}를 받아서 만들어야 그 설정이 적용된다.
 * {@code RestClient.create()}로 직접 만들면 타임아웃 없는 클라이언트가 된다.
 */
@Slf4j
@Component
public class OpinetClient {

    /** 휘발유. 응답 배열의 첫 항목은 고급휘발유(B034)라 순서로 고르면 25% 비싼 값을 쓴다. */
    private static final String GASOLINE_PRODUCT_CODE = "B027";

    private final RestClient restClient;
    private final OpinetProperties properties;
    private final ObjectMapper objectMapper;

    public OpinetClient(RestClient.Builder builder, OpinetProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = builder.build();
    }

    /**
     * 전국 평균 휘발유가(원/L).
     *
     * <p>조회는 됐지만 값을 못 믿을 때(빈 배열·휘발유 항목 부재·숫자가 아닌 PRICE)는 empty다.
     * 특히 <b>잘못된 키도 HTTP 200 + 빈 배열</b>을 주므로 상태코드만으로는 인증 실패를 알 수 없다.
     * 호출 자체가 실패하면 다른 외부 클라이언트와 같이 {@link TransitException}을 던진다.
     */
    public Optional<Integer> fetchAverageGasolinePrice() {
        String body = requestBody();
        OpinetPriceResponse response = parseBody(body);

        List<OpinetPriceResponse.Oil> oils =
                response.result() == null ? null : response.result().oil();
        if (oils == null || oils.isEmpty()) {
            log.warn("오피넷 유가 응답이 비어 있다 — API 키가 잘못됐을 수 있다(잘못된 키도 200 + 빈 배열을 준다)");
            return Optional.empty();
        }

        Optional<OpinetPriceResponse.Oil> gasoline = oils.stream()
                .filter(oil -> GASOLINE_PRODUCT_CODE.equals(oil.prodcd()))
                .findFirst();
        if (gasoline.isEmpty()) {
            // 다른 유종으로 대체하지 않는다 — 고급휘발유나 경유 값을 휘발유로 쓰는 것이 더 나쁘다.
            log.warn("오피넷 응답에 휘발유({}) 항목이 없다: prodcd={}", GASOLINE_PRODUCT_CODE,
                    oils.stream().map(OpinetPriceResponse.Oil::prodcd).toList());
            return Optional.empty();
        }
        return parsePrice(gasoline.get().price());
    }

    /**
     * 오피넷은 JSON을 {@code Content-Type: text/html}로 내려준다(실측). 그래서
     * {@code .body(OpinetPriceResponse.class)}는 컨버터를 못 찾아 실패한다 — 문자열로 받아
     * 직접 파싱한다. 응답 앞뒤에 공백·개행이 잔뜩 섞여 있지만 Jackson이 견딘다.
     */
    private String requestBody() {
        try {
            URI uri = URI.create(properties.baseUrl() + "/avgAllPrice.do"
                    + "?out=json&code=" + properties.apiKey());
            return restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException | IllegalArgumentException e) {
            // URL에 apiKey(code 파라미터)가 붙으므로 예외 메시지(URL 포함) 대신 타입만 남긴다.
            log.warn("오피넷 유가 조회 실패: {}", e.getClass().getSimpleName());
            throw new TransitException(TransitErrorCode.OPINET_API_CALL_FAILED);
        }
    }

    private OpinetPriceResponse parseBody(String body) {
        try {
            return objectMapper.readValue(body, OpinetPriceResponse.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // 점검 안내 HTML 등 JSON이 아닌 응답. 200으로 오므로 여기서만 걸린다.
            log.warn("오피넷 유가 응답을 파싱할 수 없다: {}", e.getMessage());
            throw new TransitException(TransitErrorCode.OPINET_API_CALL_FAILED);
        }
    }

    /** PRICE는 "1866.69" 같은 소수점 문자열이다 — 정수로 바로 파싱하면 깨진다. */
    private Optional<Integer> parsePrice(String price) {
        if (price == null || price.isBlank()) {
            log.warn("오피넷 휘발유 PRICE가 비어 있다");
            return Optional.empty();
        }
        try {
            return Optional.of((int) Math.round(Double.parseDouble(price.trim())));
        } catch (NumberFormatException e) {
            log.warn("오피넷 휘발유 PRICE를 숫자로 읽을 수 없다: {}", price);
            return Optional.empty();
        }
    }
}
