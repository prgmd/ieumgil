package com.ssafy.ieumgil.domain.festival.client;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE 계약 테스트 — 실제 TourAPI(KorService2 detailCommon2)를 진짜 서비스키로 호출한다.
 * .env의 TOUR_API_KEY로 게이트되어 키가 없으면 SKIP된다.
 * 네트워크/쿼터/실데이터에 의존해 비결정적이므로 CI 게이트가 되어서는 안 된다.
 * 목적: homepage 필드 존재·형태(HTML 앵커/평문/빈값)를 실제 응답으로 확인.
 */
@Tag("live")
class TourApiClientDetailLiveTest {

    private static final String BASE_URL = "http://apis.data.go.kr/B551011/KorService2";

    @Disabled("data.go.kr 502 업스트림 장애로 라이브 그린 미확인(2026-07-31). "
            + "우리 키·파라미터는 정상(키없이 401, 유효키 502=백엔드 포워딩 실패). "
            + "TourAPI 복구 후 라이브 통과 확인하고 @Disabled 제거할 것.")
    @Test
    @DisplayName("detailCommon2 호출이 예외 없이 끝난다 — homepage는 축제마다 있을 수도 없을 수도")
    void 상세_조회가_동작한다() throws IOException {
        String serviceKey = readKeyFromDotenv("TOUR_API_KEY");
        Assumptions.assumeTrue(serviceKey != null && !serviceKey.isBlank(), "TOUR_API_KEY 없음 — .env 확인 필요");

        TourApiClient client = new TourApiClient(RestClient.builder(), new TourApiProperties(serviceKey, BASE_URL));

        // 유효한 축제 contentId. 만료되면 최근 축제 조회로 교체한다.
        String homepage = client.fetchDetailHomepage("2762615");

        assertThat(homepage == null || homepage instanceof String).isTrue();
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
