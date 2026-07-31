package com.ssafy.ieumgil.domain.transit.client;

import com.ssafy.ieumgil.domain.transit.dto.OdsayBusScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayBusTerminalResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayFlightScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainTerminalResponse;
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
 * LIVE 계약 테스트 — 실제 ODsay API를 진짜 키로 호출한다.
 * .env의 ODSAY_API_KEY로 게이트되어 키가 없으면 SKIP된다.
 * 네트워크/쿼터/실데이터에 의존해 비결정적이므로 CI 게이트가 되어서는 안 된다.
 * 목적: "test-key" MockRestServiceServer 테스트가 못 잡는 실제 스키마/인증/파라미터 드리프트 감지.
 */
class OdsayClientLiveTest {

	private static final String BASE_URL = "https://api.odsay.com/v1/api";

	// 부산 실좌표 — 대중교통 경로가 나오도록 충분히 떨어뜨림(부산역 ~ 해운대해수욕장, 약 15km).
	private static final double BUSAN_STATION_LAT = 35.1151;
	private static final double BUSAN_STATION_LNG = 129.0413;
	private static final double HAEUNDAE_LAT = 35.1587;
	private static final double HAEUNDAE_LNG = 129.1604;

	@Test
	void publicTransitRouteReturnsPathWithPositiveTotalTime() throws IOException {
		OdsayClient client = liveClient();

		Optional<OdsayRouteResponse.Path> result = client.searchPublicTransitRoute(
				BUSAN_STATION_LAT, BUSAN_STATION_LNG, HAEUNDAE_LAT, HAEUNDAE_LNG, "ANY");

		assertThat(result).isPresent();
		assertThat(result.get().info().totalTime()).isGreaterThan(0);
	}

	@Test
	void trainTerminalSearchThenScheduleReturnsRealDepartures() throws IOException {
		OdsayClient client = liveClient();

		Optional<OdsayTrainTerminalResponse.Terminal> seoul = client.searchTrainTerminal("서울");
		assertThat(seoul).isPresent();
		assertThat(seoul.get().stationName()).isNotBlank();
		assertThat(seoul.get().arrivalTerminals()).isNotEmpty();

		OdsayTrainTerminalResponse.Point destination = seoul.get().arrivalTerminals().get(0);
		assertThat(destination.stationID()).isNotZero();

		List<OdsayTrainScheduleResponse.Train> trains =
				client.getTrainSchedule(seoul.get().stationID(), destination.stationID());

		assertThat(trains).isNotEmpty();
		assertThat(trains.get(0).departureTime()).isNotBlank();
		assertThat(trains.get(0).arrivalTime()).isNotBlank();
	}

	@Test
	void intercityBusTerminalSearchThenScheduleReturnsRealDepartures() throws IOException {
		OdsayClient client = liveClient();

		Optional<OdsayBusTerminalResponse.Terminal> seoul = client.searchIntercityBusTerminal("서울");
		assertThat(seoul).isPresent();
		assertThat(seoul.get().stationName()).isNotBlank();
		assertThat(seoul.get().destinationTerminals()).isNotEmpty();

		OdsayBusTerminalResponse.Point destination = seoul.get().destinationTerminals().get(0);
		assertThat(destination.stationID()).isNotZero();

		List<OdsayBusScheduleResponse.Bus> buses =
				client.getIntercityBusSchedule(seoul.get().stationID(), destination.stationID());

		assertThat(buses).isNotEmpty();
		assertThat(buses.get(0).departureTime()).isNotBlank();
		assertThat(buses.get(0).fare()).isGreaterThan(0);
	}

	@Test
	void flightScheduleGimpoToJejuReturnsRealDepartures() throws IOException {
		OdsayClient client = liveClient();

		List<OdsayFlightScheduleResponse.Flight> flights = client.getFlightSchedule(
				DomesticAirport.GIMPO.stationId(), DomesticAirport.JEJU.stationId());

		assertThat(flights).isNotEmpty();
		assertThat(flights.get(0).airline()).isNotBlank();
		assertThat(flights.get(0).flight()).isNotBlank();
	}

	private OdsayClient liveClient() throws IOException {
		String key = readKeyFromDotenv("ODSAY_API_KEY");
		Assumptions.assumeTrue(key != null && !key.isBlank(), "ODSAY_API_KEY 없음 — .env 확인 필요");
		return new OdsayClient(RestClient.builder(), new OdsayProperties(key, BASE_URL));
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
