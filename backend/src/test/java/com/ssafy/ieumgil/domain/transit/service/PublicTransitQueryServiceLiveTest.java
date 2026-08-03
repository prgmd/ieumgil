package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.client.OdsayProperties;
import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import org.junit.jupiter.api.Assumptions;
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
 * LIVE 계약 테스트 — 실제 ODsay API를 진짜 키로 호출한다.
 * .env의 ODSAY_API_KEY로 게이트되어 키가 없으면 SKIP된다.
 * 네트워크/쿼터/실데이터에 의존해 비결정적이므로 CI 게이트가 되어서는 안 된다.
 * 목적: PublicTransitQueryService.getCombinedRoute가 실제 ODsay 응답을
 * 우리 DTO로 올바른 단위로 파싱하는지 감지("test-key" 목 테스트로는 못 잡음).
 */
@Tag("live")
@DisplayName("[live] ODsay 통합 대중교통 조회 계약")
class PublicTransitQueryServiceLiveTest {

	private static final String BASE_URL = "https://api.odsay.com/v1/api";

	// 서울시청 ~ 강남역 실좌표.
	private static final double CITY_HALL_LAT = 37.5666;
	private static final double CITY_HALL_LNG = 126.9784;
	private static final double GANGNAM_LAT = 37.4979;
	private static final double GANGNAM_LNG = 127.0276;

	@Test
	@DisplayName("서울시청→강남역 대중교통 경로가 상식 범위의 값으로 파싱된다")
	void combinedRouteReturnsSaneValues() throws IOException {
		PublicTransitQueryService service = liveService();

		TransitResDTO.Route route =
				service.getCombinedRoute(CITY_HALL_LAT, CITY_HALL_LNG, GANGNAM_LAT, GANGNAM_LNG);

		// ">0"만 보지 않는다. 단위가 틀리면(초/분, 원/십원) 여기서 걸린다 —
		// 2026-08-02에 소요시간 60배 버그를 통과시킨 것이 정확히 ">0" assert였다.
		assertThat(route.durationMin()).isBetween(15, 90);
		assertThat(route.fare()).isBetween(1000, 5000);
		assertThat(route.fareConfidence()).isEqualTo(TransitResDTO.FareConfidence.CONFIRMED);
	}

	private PublicTransitQueryService liveService() throws IOException {
		String key = readKeyFromDotenv("ODSAY_API_KEY");
		Assumptions.assumeTrue(key != null && !key.isBlank(), "ODSAY_API_KEY 없음 — .env 확인 필요");
		OdsayClient client = new OdsayClient(RestClient.builder(), new OdsayProperties(key, BASE_URL));
		return new PublicTransitQueryServiceImpl(client);
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
