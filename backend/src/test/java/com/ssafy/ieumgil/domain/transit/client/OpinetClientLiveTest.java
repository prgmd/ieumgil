package com.ssafy.ieumgil.domain.transit.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE 계약 테스트 — 실제 오피넷 유가 API를 진짜 키로 호출한다.
 * .env의 OPINET_API_KEY로 게이트되어 키가 없으면 SKIP된다.
 * 네트워크/실데이터에 의존해 비결정적이므로 CI 게이트가 되어서는 안 된다.
 * 목적: "test-key" MockRestServiceServer 테스트가 못 잡는 실제 스키마/인증/Content-Type 드리프트 감지.
 */
@Tag("live")
class OpinetClientLiveTest {

    private static final String BASE_URL = "https://www.opinet.co.kr/api";

    @Test
    @DisplayName("실제 전국 평균 휘발유가가 상식 범위(1000~3000원/L) 안으로 온다")
    void fetchAverageGasolinePriceReturnsSanePrice() throws IOException {
        String apiKey = readKeyFromDotenv("OPINET_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "OPINET_API_KEY 없음 — .env 확인 필요");

        OpinetClient client = new OpinetClient(
                RestClient.builder(), new OpinetProperties(apiKey, BASE_URL), new ObjectMapper());

        Optional<Integer> price = client.fetchAverageGasolinePrice();

        // "0보다 크다"로는 부족하다 — 소수점을 잘못 처리해 186669가 되어도 통과한다.
        // 이 저장소에서 그 assert가 실제로 60배 버그를 통과시켰다.
        // (유종 오선택은 이 범위로 못 잡는다 — 고급휘발유도 2341원이라 범위 안이다. 그쪽은 fixture 단위 테스트 담당)
        assertThat(price).isPresent();
        assertThat(price.get()).isBetween(1000, 3000);
    }

    private String readKeyFromDotenv(String name) throws IOException {
        Path envFile = Path.of(".env");
        if (!Files.exists(envFile)) {
            return null;
        }
        Properties props = new Properties();
        try (var reader = Files.newBufferedReader(envFile)) {
            props.load(reader);
        }
        return props.getProperty(name);
    }
}
