package com.ssafy.ieumgil.domain.place.client;

import com.ssafy.ieumgil.domain.place.dto.KakaoAddressResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoDirectionsResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoPlaceResponse;
import com.ssafy.ieumgil.domain.place.dto.KakaoWalkingRouteResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE 계약 테스트 — 실제 카카오 로컬/모빌리티 API를 진짜 키로 호출한다.
 * .env의 KAKAO_REST_API_KEY로 게이트되어 키가 없으면 SKIP된다.
 * 네트워크/쿼터/실데이터에 의존해 비결정적이므로 CI 게이트가 되어서는 안 된다.
 * 목적: "test-key" MockRestServiceServer 테스트가 못 잡는 실제 스키마/인증/파라미터 드리프트 감지.
 */
@Tag("live")
class KakaoLocalClientLiveTest {

	private static final String BASE_URL = "https://dapi.kakao.com";

	// 부산 실좌표
	private static final double HAEUNDAE_LAT = 35.1587;
	private static final double HAEUNDAE_LNG = 129.1604;
	private static final double HAEUNDAE_NEAR_LAT = 35.1600;
	private static final double HAEUNDAE_NEAR_LNG = 129.1650;
	private static final double BUSAN_STATION_LAT = 35.1151;
	private static final double BUSAN_STATION_LNG = 129.0413;

	@Test
	void keywordSearchReturnsRealDocuments() throws IOException {
		KakaoLocalClient client = liveClient();

		List<KakaoPlaceResponse.Document> docs = client.searchByKeyword("부산 해운대해수욕장", null, null);

		assertThat(docs).isNotEmpty();
		KakaoPlaceResponse.Document first = docs.get(0);
		assertThat(first.place_name()).isNotBlank();
		assertThat(first.x()).isNotBlank();
		assertThat(first.y()).isNotBlank();
		// x/y는 문자열 좌표 — 실제로 파싱 가능한 숫자여야 한다.
		assertThat(Double.parseDouble(first.x())).isBetween(124.0, 132.0);
		assertThat(Double.parseDouble(first.y())).isBetween(33.0, 39.0);
	}

	@Test
	void coord2AddressReturnsRealRegion() throws IOException {
		KakaoLocalClient client = liveClient();

		// 해변/해상 좌표는 주소가 없어 빈 응답이 오므로 육지의 건물 좌표(부산역)를 사용한다.
		Optional<KakaoAddressResponse.Document> result = client.coord2Address(BUSAN_STATION_LAT, BUSAN_STATION_LNG);

		assertThat(result).isPresent();
		KakaoAddressResponse.Document doc = result.get();
		// road_address는 도로명 미부여 시 null일 수 있으므로 둘 중 하나라도 주소가 있으면 통과.
		boolean hasAddress =
				(doc.address() != null && doc.address().address_name() != null && !doc.address().address_name().isBlank())
						|| (doc.road_address() != null && doc.road_address().address_name() != null
						&& !doc.road_address().address_name().isBlank());
		assertThat(hasAddress).isTrue();
		if (doc.address() != null && doc.address().address_name() != null) {
			assertThat(doc.address().address_name()).contains("부산");
		}
	}

	@Test
	void walkingRouteReturnsPositiveDistanceAndDuration() throws IOException {
		KakaoLocalClient client = liveClient();

		Optional<KakaoWalkingRouteResponse.Properties> result =
				client.getWalkingRoute(HAEUNDAE_LAT, HAEUNDAE_LNG, HAEUNDAE_NEAR_LAT, HAEUNDAE_NEAR_LNG);

		assertThat(result).isPresent();
		assertThat(result.get().totalDistance()).isGreaterThan(0);
		assertThat(result.get().totalTime()).isGreaterThan(0);
	}

	@Test
	void drivingRouteReturnsPositiveFareDistanceDuration() throws IOException {
		KakaoLocalClient client = liveClient();

		Optional<KakaoDirectionsResponse.Summary> result =
				client.getDrivingRoute(BUSAN_STATION_LAT, BUSAN_STATION_LNG, HAEUNDAE_LAT, HAEUNDAE_LNG);

		assertThat(result).isPresent();
		assertThat(result.get().fare().taxi()).isGreaterThan(0);
		assertThat(result.get().distance()).isGreaterThan(0);
		assertThat(result.get().duration()).isGreaterThan(0);
	}

	private KakaoLocalClient liveClient() throws IOException {
		String key = readKeyFromDotenv("KAKAO_REST_API_KEY");
		Assumptions.assumeTrue(key != null && !key.isBlank(), "KAKAO_REST_API_KEY 없음 — .env 확인 필요");
		return new KakaoLocalClient(RestClient.builder(), new KakaoLocalProperties(key, BASE_URL));
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
