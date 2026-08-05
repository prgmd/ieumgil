package com.ssafy.ieumgil.domain.transit;

import com.ssafy.ieumgil.domain.transit.client.DomesticAirport;
import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.client.OdsayProperties;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.TransitScheduleQueryService;
import com.ssafy.ieumgil.domain.transit.service.TransitScheduleQueryServiceImpl;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE 계약 테스트 — 실제 ODsay API를 진짜 키로 호출해 이 브랜치가 딛고 선 설계 전제를 고정한다.
 * .env의 ODSAY_API_KEY로 게이트되어 키가 없으면 SKIP된다.
 * 네트워크/쿼터/실데이터에 의존해 비결정적이므로 CI 게이트가 되어서는 안 된다.
 *
 * <p>목적: "시내에는 payment가 오고 시외에는 오지 않는다", "항공에는 요금 필드가 없다",
 * "고속버스에는 도착시각이 없다" 같은 전제가 실제 응답과 어긋나면 여기서 잡아낸다 —
 * "test-key" MockRestServiceServer 목 테스트는 우리가 만든 픽스처만 검증하므로 이 드리프트를 못 잡는다.
 */
@Tag("live")
@DisplayName("[live] ODsay 응답 필드 계약 — 설계 전제를 고정한다")
class OdsayFieldContractLiveTest {

	private static final String BASE_URL = "https://api.odsay.com/v1/api";

	// 서울시청 ~ 강남역 실좌표 (시내).
	private static final double CITY_HALL_LAT = 37.5666;
	private static final double CITY_HALL_LNG = 126.9784;
	private static final double GANGNAM_LAT = 37.4979;
	private static final double GANGNAM_LNG = 127.0276;

	// 부산역 실좌표 (서울시청 기준 약 400km, 시외).
	private static final double BUSAN_STATION_LAT = 35.1151;
	private static final double BUSAN_STATION_LNG = 129.0413;

	// 제주국제공항 실좌표 (부산역 기준 시외·도서).
	private static final double JEJU_AIRPORT_LAT = 33.5113;
	private static final double JEJU_AIRPORT_LNG = 126.4930;

	@Test
	@DisplayName("시내 경로에는 요금·배차·거리·환승 상세가 온다")
	void 시내_경로_필드_계약() throws IOException {
		OdsayClient client = liveOdsayClient();

		List<OdsayRouteResponse.Path> paths = client.searchPublicTransitRoute(
				CITY_HALL_LAT, CITY_HALL_LNG, GANGNAM_LAT, GANGNAM_LNG, "ANY");

		assertThat(paths).isNotEmpty();
		OdsayRouteResponse.Info info = paths.get(0).info();
		System.out.println("[시내 계약] pathType=" + paths.get(0).pathType()
				+ ", payment=" + info.payment()
				+ ", totalIntervalTime=" + info.totalIntervalTime()
				+ ", totalDistance=" + info.totalDistance());

		assertThat(info.payment()).as("시내 경로 요금").isNotNull().isPositive();
		assertThat(info.totalIntervalTime()).as("시내 경로 배차 간격").isNotNull();
		// 서울시청→강남역 실거리는 약 12,841m로 알려져 있다 — 5,000m는 안전 하한이다.
		assertThat(info.totalDistance()).as("시내 경로 거리").isNotNull().isGreaterThan(5_000);
		assertThat(paths.get(0).subPath()).as("환승 상세(subPath)").isNotEmpty();
	}

	@Test
	@DisplayName("시외 경로에는 요금이 오지 않는다 — 이 전제가 깨지면 UNKNOWN 처리를 재검토해야 한다")
	void 시외_경로_요금_부재_계약() throws IOException {
		OdsayClient client = liveOdsayClient();

		List<OdsayRouteResponse.Path> paths = client.searchPublicTransitRoute(
				CITY_HALL_LAT, CITY_HALL_LNG, BUSAN_STATION_LAT, BUSAN_STATION_LNG, "ANY");

		assertThat(paths).isNotEmpty();
		Set<Integer> pathTypes = paths.stream().map(OdsayRouteResponse.Path::pathType).collect(Collectors.toSet());
		System.out.println("[시외 계약] 관측된 pathType 집합=" + pathTypes
				+ " (해운=14 포함 여부 확인용)");

		// paths.get(0)만 보지 않는다 — 전제는 "시외 경로 전부에 payment가 없다"는 것이다.
		assertThat(paths).as("시외 경로에 payment가 생겼다면 설계 근거가 바뀐다").allSatisfy(
				path -> assertThat(path.info().payment()).isNull());
		// payment가 없다고 요금이 없는 게 아니다 — totalPayment로 온다(2026-08-04 실측).
		assertThat(paths).as("시외 경로는 totalPayment로 요금을 준다").allSatisfy(
				path -> assertThat(path.info().totalPayment()).isNotNull());
		// 서울시청→부산역 실거리는 약 400km로 알려져 있다 — 300,000m는 안전 하한이다.
		assertThat(paths.get(0).info().totalDistance()).as("시외 경로 거리")
				.isNotNull().isGreaterThan(300_000);

		// 교차 모드 역 이름 재사용 — 파이프라인은 이 경로의 firstStartStation/lastEndStation을
		// 기차·고속버스·항공 세 조회에 그대로 넘긴다(TransitCandidateServiceImpl). 예전에는
		// DomesticAirport가 "부산" 같은 등록된 공항명만 정확히 일치시켜서, ODsay가 도시명으로
		// "서울"을 주는 서울발 조회에서 항공만 조용히 조회 불가가 됐다(김포≠서울).
		// DomesticAirport에 도시명 별칭을 추가했으니(서울→김포 등) 이제 세 수단 모두 풀려야 한다.
		String from = paths.get(0).info().firstStartStation();
		String to = paths.get(0).info().lastEndStation();
		TransitScheduleQueryService scheduleQueryService = new TransitScheduleQueryServiceImpl(client);
		boolean trainResolves = scheduleQueryService.searchTrainStation(from).isPresent()
				&& scheduleQueryService.searchTrainStation(to).isPresent();
		boolean busResolves = busTerminalResolves(scheduleQueryService, from)
				&& busTerminalResolves(scheduleQueryService, to);
		boolean airResolves = DomesticAirport.findByName(from).isPresent()
				&& DomesticAirport.findByName(to).isPresent();
		System.out.println("[교차 모드 이름 재사용] firstStartStation=\"" + from + "\", lastEndStation=\"" + to
				+ "\" — 기차 매칭=" + trainResolves + ", 고속버스 매칭=" + busResolves + ", 항공 매칭=" + airResolves);

		assertThat(trainResolves).as("기차 조회가 시외 경로 역 이름으로 풀려야 한다").isTrue();
		assertThat(busResolves).as("고속버스 조회가 시외 경로 역 이름으로 풀려야 한다").isTrue();
		assertThat(airResolves).as("DomesticAirport 도시명 별칭 추가 후 항공도 풀려야 한다(서울→김포)").isTrue();
	}

	@Test
	@DisplayName("부산-제주처럼 첫 매칭 시외 경로가 항공 경로 자신이면 공항 전체 명칭이 온다 — 정규화 후 항공이 풀려야 한다")
	void 부산_제주_항공_전체_명칭_계약() throws IOException {
		OdsayClient client = liveOdsayClient();

		List<OdsayRouteResponse.Path> paths = client.searchPublicTransitRoute(
				BUSAN_STATION_LAT, BUSAN_STATION_LNG, JEJU_AIRPORT_LAT, JEJU_AIRPORT_LNG, "ANY");

		assertThat(paths).isNotEmpty();
		Set<Integer> intercityPathTypes = Set.of(11, 12, 13, 14, 20);
		// TransitCandidateServiceImpl.RoadResult.intercityPath()와 같은 규칙 — 시외 판정 목록에서
		// 처음 매칭되는 경로 하나를 그대로 쓴다.
		OdsayRouteResponse.Path firstIntercity = paths.stream()
				.filter(p -> intercityPathTypes.contains(p.pathType()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("시외로 분류될 경로가 하나도 없다: " + paths));

		String from = firstIntercity.info().firstStartStation();
		String to = firstIntercity.info().lastEndStation();
		System.out.println("[부산-제주 계약] pathType=" + firstIntercity.pathType()
				+ ", firstStartStation=\"" + from + "\", lastEndStation=\"" + to + "\"");

		// Task 14 실측(2026-08-04)에서 첫 매칭 경로가 pathType=13(항공) 자신이었고, 도시명이 아니라
		// "김해국제공항"/"제주국제공항" 같은 공항 전체 명칭을 줬다 — 접미사 정규화 전에는 둘 다 실패해
		// 부산→제주 항공 후보가 통째로 unavailable로 나왔다.
		boolean airResolves = DomesticAirport.findByName(from).isPresent()
				&& DomesticAirport.findByName(to).isPresent();
		assertThat(airResolves)
				.as("공항 전체 명칭(\"" + from + "\"/\"" + to + "\") 접미사 정규화 후 항공이 풀려야 한다")
				.isTrue();
	}

	private boolean busTerminalResolves(TransitScheduleQueryService service, String name) {
		return service.searchExpressBusTerminal(name).isPresent()
				|| service.searchIntercityBusTerminal(name).isPresent();
	}

	@Test
	@DisplayName("기차 시간표에는 등급별 요금이 오고, runDay는 한글 표기다")
	void 기차_요금_계약() throws IOException {
		TransitScheduleQueryService scheduleQueryService = liveScheduleService();

		Optional<TransitScheduleResDTO.TerminalSearchResult> seoul = scheduleQueryService.searchTrainStation("서울");
		Optional<TransitScheduleResDTO.TerminalSearchResult> busan = scheduleQueryService.searchTrainStation("부산");
		assertThat(seoul).as("서울역 검색").isPresent();
		assertThat(busan).as("부산역 검색").isPresent();

		List<TransitScheduleResDTO.TrainSchedule> schedules = scheduleQueryService.getTrainSchedule(
				seoul.get().stationId(), busan.get().stationId(), LocalDate.now().plusDays(7));

		assertThat(schedules).isNotEmpty();
		Set<String> runDays = schedules.stream().map(TransitScheduleResDTO.TrainSchedule::runDay)
				.collect(Collectors.toSet());
		System.out.println("[기차 계약] 관측된 runDay 집합=" + runDays
				+ " (매일/토일/목/월수금 외 평일·주말·휴일·목요일 같은 전체 표기 여부 확인용)");

		TransitScheduleResDTO.TrainSchedule withFare = schedules.stream()
				.filter(t -> t.generalFare() != null)
				.findFirst()
				.orElseThrow(() -> new AssertionError("일반석 요금이 있는 열차가 하나도 없다: " + schedules));
		System.out.println("[기차 계약] railName=" + withFare.railName() + ", generalFare=" + withFare.generalFare()
				+ ", specialFare=" + withFare.specialFare() + ", standingFare=" + withFare.standingFare());

		assertThat(withFare.generalFare()).as("기차 일반석 요금").isPositive();
		assertThat(withFare.departureTime()).matches("\\d{2}:\\d{2}");
		assertThat(withFare.arrivalTime()).matches("\\d{2}:\\d{2}");
	}

	@Test
	@DisplayName("항공 시간표에는 요금 필드가 없다")
	void 항공_요금_부재_계약() throws IOException {
		TransitScheduleQueryService scheduleQueryService = liveScheduleService();

		List<TransitScheduleResDTO.FlightSchedule> flights = scheduleQueryService.getFlightSchedule(
				DomesticAirport.GIMPO.stationId(), DomesticAirport.JEJU.stationId(), LocalDate.now().plusDays(7));

		assertThat(flights).isNotEmpty();
		Set<String> runDays = flights.stream().map(TransitScheduleResDTO.FlightSchedule::runDay)
				.collect(Collectors.toSet());
		System.out.println("[항공 계약] 관측된 runDay 집합=" + runDays);

		assertThat(flights.get(0).airline()).isNotBlank();
		assertThat(flights.get(0).departureTime()).matches("\\d{2}:\\d{2}");
		// FlightSchedule 레코드 자체에 요금 필드가 없다 — 컴파일 타임에 이미 고정되어 있다(ODsay가 주지 않는다).
	}

	@Test
	@DisplayName("고속버스 시간표에는 요금이 있고 runDay·도착시각 필드가 없다")
	void 고속버스_필드_계약() throws IOException {
		String apiKey = readKeyFromDotenv("ODSAY_API_KEY");
		Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ODSAY_API_KEY 없음 — .env 확인 필요");
		OdsayClient client = new OdsayClient(RestClient.builder(), new OdsayProperties(apiKey, BASE_URL));
		TransitScheduleQueryService scheduleQueryService = new TransitScheduleQueryServiceImpl(client);

		Optional<TransitScheduleResDTO.TerminalSearchResult> seoul =
				scheduleQueryService.searchExpressBusTerminal("서울")
						.or(() -> scheduleQueryService.searchIntercityBusTerminal("서울"));
		assertThat(seoul).as("서울 고속/시외버스 터미널 검색").isPresent();
		TransitScheduleResDTO.Terminal busan = seoul.get().destinations().stream()
				.filter(d -> d.stationName().contains("부산"))
				.findFirst()
				.orElseGet(() -> seoul.get().destinations().get(0));

		List<TransitScheduleResDTO.BusSchedule> buses = scheduleQueryService.getIntercityBusSchedule(
				seoul.get().stationId(), busan.stationId(), LocalDate.now().plusDays(7));

		assertThat(buses).isNotEmpty();
		long missingFareCount = buses.stream().filter(b -> b.fare() == null).count();
		System.out.println("[고속버스 계약] 편수=" + buses.size() + ", fare 없는 편수=" + missingFareCount);
		buses.forEach(b -> System.out.println(
				"  busClass=" + b.busClass() + ", fare=" + b.fare() + ", wasteTimeMin=" + b.wasteTimeMin()));

		TransitScheduleResDTO.BusSchedule withFare = buses.stream()
				.filter(b -> b.fare() != null)
				.findFirst()
				.orElseThrow(() -> new AssertionError("요금이 있는 고속버스가 하나도 없다: " + buses));
		assertThat(withFare.fare()).as("고속버스 요금").isPositive();
		// BusSchedule 레코드 자체에 arrivalTime 필드가 없다 — 출발+소요시간으로 계산해서 쓴다.

		// 원시 응답에도 runDay 키가 없는지 확인한다 — 우리가 매핑을 안 한 것이 아니라
		// ODsay가 애초에 주지 않는 것인지를 구분한다.
		String rawBody = fetchRawIntercityBusScheduleJson(seoul.get().stationId(), busan.stationId(), apiKey);
		System.out.println("[고속버스 계약] 원시 응답에 \"runDay\" 포함 여부=" + rawBody.contains("runDay"));
		assertThat(rawBody).as("고속버스 원시 응답에 runDay 키가 없다").doesNotContain("runDay");
	}

	private String fetchRawIntercityBusScheduleJson(int startStationId, int endStationId, String apiKey) {
		RestClient rawClient = RestClient.builder().build();
		URI uri = URI.create(BASE_URL + "/searchInterBusSchedule"
				+ "?startStationID=" + startStationId
				+ "&endStationID=" + endStationId
				+ "&apiKey=" + apiKey);
		return rawClient.get().uri(uri).retrieve().body(String.class);
	}

	private OdsayClient liveOdsayClient() throws IOException {
		String key = readKeyFromDotenv("ODSAY_API_KEY");
		Assumptions.assumeTrue(key != null && !key.isBlank(), "ODSAY_API_KEY 없음 — .env 확인 필요");
		return new OdsayClient(RestClient.builder(), new OdsayProperties(key, BASE_URL));
	}

	private TransitScheduleQueryService liveScheduleService() throws IOException {
		return new TransitScheduleQueryServiceImpl(liveOdsayClient());
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
