package com.ssafy.ieumgil.domain.festival.client;

import com.ssafy.ieumgil.domain.festival.dto.TourApiResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE 계약 테스트 — 실제 TourAPI(KorService2 축제 조회)를 진짜 서비스키로 호출한다.
 * .env의 TOUR_API_KEY로 게이트되어 키가 없으면 SKIP된다.
 * 네트워크/쿼터/실데이터에 의존해 비결정적이므로 CI 게이트가 되어서는 안 된다.
 * 목적: "test-key" MockRestServiceServer 테스트가 못 잡는 실제 스키마/인증/파라미터 드리프트 감지.
 */
@Tag("live")
class TourApiClientLiveTest {

	private static final String BASE_URL = "http://apis.data.go.kr/B551011/KorService2";
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

	@Disabled("data.go.kr 502 업스트림 장애로 라이브 그린 미확인(2026-07-31). "
			+ "우리 키·파라미터는 정상(키없이 401, 유효키 502=백엔드 포워딩 실패). "
			+ "TourAPI 복구 후 라이브 통과 확인하고 @Disabled 제거할 것.")
	@Test
	void searchFestivalsReturnsRealItems() throws IOException {
		String serviceKey = readKeyFromDotenv("TOUR_API_KEY");
		Assumptions.assumeTrue(serviceKey != null && !serviceKey.isBlank(), "TOUR_API_KEY 없음 — .env 확인 필요");

		TourApiClient client = new TourApiClient(RestClient.builder(), new TourApiProperties(serviceKey, BASE_URL));

		// 나이틀리 배치와 동일한 호출. 이달 1일부터 시작하는 축제는 상시 다수 존재하므로 데이터가 비지 않는다.
		String eventStartDate = LocalDate.now().withDayOfMonth(1).format(YYYYMMDD);

		List<TourApiResponse.Item> items = client.searchFestivals(eventStartDate, 1, 100);

		assertThat(items).isNotEmpty();
		TourApiResponse.Item first = items.get(0);
		assertThat(first.contentid()).isNotBlank();
		assertThat(first.title()).isNotBlank();
		assertThat(first.eventstartdate()).isNotBlank();
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
