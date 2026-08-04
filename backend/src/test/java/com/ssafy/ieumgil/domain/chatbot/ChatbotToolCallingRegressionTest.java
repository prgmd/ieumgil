package com.ssafy.ieumgil.domain.chatbot;

import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FlightScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceCoordinateResolver;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.BusScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TrainScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.WalkingRouteTool;
import com.ssafy.ieumgil.domain.festival.RegionCode;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.domain.chatbot.ChatbotMode;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotPrompt;
import com.ssafy.ieumgil.domain.chatbot.service.TripContextBuilder;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.BusScheduleProvider;
import com.ssafy.ieumgil.domain.transit.service.FlightScheduleProvider;
import com.ssafy.ieumgil.domain.transit.service.TrainScheduleProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.web.client.RestClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실모델(GMS) + 전체 tool 세트를 등록한 통합 tool-selection 회귀 테스트.
 *
 * 프로덕션 {@code ChatbotCommandServiceImpl.callGms}를 미러링한다: 동일한 시스템 프롬프트,
 * 동일한 실모델 빌드(GmsConnectionTest 방식), 그리고 목적지+여행기간이 채워진 프로젝트가 갖는
 * 전체 tool 세트(장소검색·도보·택시·기차·버스·항공·축제)를 매 요청마다 등록한다.
 * 각 tool의 다운스트림은 모두 mock 처리되어 실제 외부 API를 호출하지 않는다.
 *
 * 검증 방식: 대표 프롬프트 1개당 목표 tool의 "고유" 다운스트림 메서드가 호출됐는지 + 다른
 * 계열 tool의 고유 다운스트림은 호출되지 않았는지(디스앰비규에이션)를 확인한다.
 * 주의: WalkingRouteTool·TaxiRouteTool은 내부 KakaoPlaceCoordinateResolver를 통해
 * KakaoPlaceSearchTool과 동일한 {@code placeQueryService.searchPlaces(...)}를 호출한다.
 * 따라서 searchPlaces 호출만으로는 어떤 tool이 선택됐는지 구분할 수 없어, 각 tool은
 * 반드시 자신만의 고유 메서드(getWalkingRoute/getTaxiRoute/findSchedule 등)로 검증한다.
 *
 * !!! 이 테스트들은 실제 모델을 호출하므로 본질적으로 비결정적이다. GMS_API_KEY(.env)로만
 * 게이팅되며 CI 게이트로 쓰면 안 된다. 플레이키하면 그 사실을 기록할 것 —
 * 초록불을 만들려고 단언을 약화시키지 말 것(엉뚱한 tool 선택은 프롬프트/설명 품질 신호다).
 */
@Tag("live")
class ChatbotToolCallingRegressionTest {

	private static final String DESTINATION = "부산";
	private static final LocalDate TRIP_START = LocalDate.of(2026, 10, 1);
	private static final LocalDate TRIP_END = LocalDate.of(2026, 10, 4);

	/** 전체 tool 세트가 공유하는 다운스트림 mock 묶음. tool별 고유 메서드로 검증한다. */
	private static final class ToolMocks {
		final PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
		final FestivalQueryService festivalQueryService = mock(FestivalQueryService.class);
		final TrainScheduleProvider trainScheduleProvider = mock(TrainScheduleProvider.class);
		final BusScheduleProvider busScheduleProvider = mock(BusScheduleProvider.class);
		final FlightScheduleProvider flightScheduleProvider = mock(FlightScheduleProvider.class);
	}

	// === 대표 프롬프트 1개당 목표 tool의 고유 다운스트림 호출 + 타 계열 미호출 검증 ===

	@Test
	void modelInvokesFestivalTool_forThingsToDoRequest() throws IOException {
		ToolMocks mocks = runAllTools("부산으로 3박 4일 여행 가는데 즐길만한 축제나 행사 추천해줘.");

		verify(mocks.festivalQueryService).findByRegionAndDateRange(any(), any(), any());
		verify(mocks.placeQueryService, never()).searchPlaces(any(), any(), any());
		verify(mocks.placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.trainScheduleProvider, never()).findSchedule(any(), any());
	}

	@Test
	void modelInvokesPlaceSearchTool_forCafeRequest() throws IOException {
		ToolMocks mocks = runAllTools("부산 해운대 근처에 분위기 좋은 카페 몇 군데 찾아줘.");

		// searchPlaces는 place-search tool의 고유(평문 검색) 경로. walk/taxi도 이를 쓰지만
		// 그 경우 getWalkingRoute/getTaxiRoute까지 호출되므로 그 두 메서드로 배제한다.
		verify(mocks.placeQueryService, atLeastOnce()).searchPlaces(any(), any(), any());
		verify(mocks.placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.festivalQueryService, never()).findByRegionAndDateRange(any(), any(), any());
		verify(mocks.trainScheduleProvider, never()).findSchedule(any(), any());
	}

	@Test
	void modelInvokesTaxiRouteTool_forInCityTaxiRequest() throws IOException {
		ToolMocks mocks = runAllTools("부산 해운대해수욕장에서 광안리해수욕장까지 택시 타면 요금 얼마나 나와?");

		verify(mocks.placeQueryService, atLeastOnce()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		// searchPlaces는 resolver가 좌표 변환에 쓰므로 택시 tool에서도 호출된다 — 단언하지 않는다.
		verify(mocks.placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.festivalQueryService, never()).findByRegionAndDateRange(any(), any(), any());
		verify(mocks.trainScheduleProvider, never()).findSchedule(any(), any());
	}

	@Test
	void modelInvokesTrainScheduleTool_forIntercityTrainRequest() throws IOException {
		ToolMocks mocks = runAllTools("서울에서 부산까지 가는 KTX 기차 시간표 좀 알려줘.");

		verify(mocks.trainScheduleProvider, atLeastOnce()).findSchedule(any(), any());
		verify(mocks.placeQueryService, never()).searchPlaces(any(), any(), any());
		verify(mocks.placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.festivalQueryService, never()).findByRegionAndDateRange(any(), any(), any());
	}

	@Test
	void modelInvokesWalkingRouteTool_forWalkRequest() throws IOException {
		ToolMocks mocks = runAllTools("부산 해운대해수욕장에서 광안리해수욕장까지 걸어서 가면 얼마나 걸려?");

		verify(mocks.placeQueryService, atLeastOnce()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		// searchPlaces는 resolver가 좌표 변환에 쓰므로 도보 tool에서도 호출된다 — 단언하지 않는다.
		verify(mocks.placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.festivalQueryService, never()).findByRegionAndDateRange(any(), any(), any());
		verify(mocks.trainScheduleProvider, never()).findSchedule(any(), any());
		verify(mocks.busScheduleProvider, never()).findSchedule(any(), any());
		verify(mocks.flightScheduleProvider, never()).findSchedule(any(), any());
	}

	@Test
	void modelInvokesBusScheduleTool_forIntercityBusRequest() throws IOException {
		ToolMocks mocks = runAllTools("서울에서 부산까지 가는 시외버스 시간표 좀 알려줘.");

		verify(mocks.busScheduleProvider, atLeastOnce()).findSchedule(any(), any());
		verify(mocks.trainScheduleProvider, never()).findSchedule(any(), any());
		verify(mocks.flightScheduleProvider, never()).findSchedule(any(), any());
		verify(mocks.placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.festivalQueryService, never()).findByRegionAndDateRange(any(), any(), any());
	}

	@Test
	void modelInvokesFlightScheduleTool_forFlightRequest() throws IOException {
		ToolMocks mocks = runAllTools("김포공항에서 제주공항까지 가는 비행기 항공편 시간표 알려줘.");

		verify(mocks.flightScheduleProvider, atLeastOnce()).findSchedule(any(), any());
		verify(mocks.trainScheduleProvider, never()).findSchedule(any(), any());
		verify(mocks.busScheduleProvider, never()).findSchedule(any(), any());
		verify(mocks.placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.festivalQueryService, never()).findByRegionAndDateRange(any(), any(), any());
	}

	@Test
	void modelDoesNotInvokeAnyTool_forAmbiguousRequest() throws IOException {
		ToolMocks mocks = runAllTools("여행 가고 싶어.");

		// 목적지·기간 없는 모호 입력 → 프롬프트의 되묻기 규칙대로 어떤 tool도 부르지 않아야 한다.
		verify(mocks.festivalQueryService, never()).findByRegionAndDateRange(any(), any(), any());
		verify(mocks.placeQueryService, never()).searchPlaces(any(), any(), any());
		verify(mocks.placeQueryService, never()).getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.placeQueryService, never()).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
		verify(mocks.trainScheduleProvider, never()).findSchedule(any(), any());
		verify(mocks.busScheduleProvider, never()).findSchedule(any(), any());
		verify(mocks.flightScheduleProvider, never()).findSchedule(any(), any());
	}

	// === 공용 헬퍼: 전체 tool 세트를 mock 다운스트림으로 구성해 실모델에 프롬프트를 던진다 ===

	/**
	 * 프로덕션 callGms와 동일하게 시스템 프롬프트 + 유저 메시지로 Prompt를 만들고,
	 * 목적지+여행기간이 있는 프로젝트의 전체 tool 세트를 등록해 실모델을 호출한다.
	 * 반환된 {@link ToolMocks}로 어떤 tool이 실제로 선택됐는지 검증한다.
	 * GMS_API_KEY가 없으면 assumeTrue로 스킵된다.
	 */
	private ToolMocks runAllTools(String userText) throws IOException {
		String apiKey = readGmsApiKeyFromDotenv();
		Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

		ToolMocks mocks = cannedMocks();
		Object[] tools = buildFullToolSet(mocks);

		// 프로덕션과 같은 프롬프트를 쓴다. 예전엔 이 파일이 프롬프트를 복사해 들고 있어서
		// 프로덕션 프롬프트를 바꿔도 이 회귀 테스트가 영향을 검증하지 못했다.
		String systemPrompt = ChatbotPrompt.SYSTEM
				+ ChatbotPrompt.modeTail(ChatbotMode.GENERAL)
				+ TripContextBuilder.build(regressionProject(), 4);
		Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userText)));
		ChatClient.builder(buildGmsChatModel(apiKey)).build()
				.prompt(prompt)
				.tools(tools)
				.call()
				.content();

		return mocks;
	}

	/** 실제 서비스가 주입하는 것과 같은 여행 메타데이터를 만든다 */
	private Project regressionProject() {
		return Project.builder()
				.destination(DESTINATION)
				.startDate(TRIP_START)
				.endDate(TRIP_END)
				.budgetHeadcount(4)
				.build();
	}

	/** 모든 다운스트림이 유효한 canned 결과를 반환하도록 스텁 — tool이 실 API 없이 완결되게 한다. */
	private ToolMocks cannedMocks() {
		ToolMocks mocks = new ToolMocks();

		PlaceResDTO.Place place = PlaceResDTO.Place.builder()
				.placeId("1")
				.name("부산 어느 장소")
				.address("부산광역시 해운대구")
				.lat(35.1587)
				.lng(129.1604)
				.category("관광")
				.build();
		when(mocks.placeQueryService.searchPlaces(any(), any(), any())).thenReturn(List.of(place));
		when(mocks.placeQueryService.getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
				.thenReturn(Optional.of(new PlaceResDTO.WalkingRoute(1200, 900)));
		when(mocks.placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
				.thenReturn(Optional.of(new PlaceResDTO.TaxiRoute(6800, 0, 3200, 600)));

		Festival festival = Festival.builder()
				.contentId("100")
				.title("부산 불꽃축제")
				.category("EV01")
				.lDongRegnCd("26")
				.addr("부산광역시 수영구")
				.eventStartDate(TRIP_START)
				.eventEndDate(TRIP_END)
				.build();
		when(mocks.festivalQueryService.findByRegionAndDateRange(any(), any(), any()))
				.thenReturn(List.of(festival));

		when(mocks.trainScheduleProvider.findSchedule(any(), any())).thenReturn(List.of(
				TransitScheduleResDTO.TrainSchedule.builder()
						.railName("경부선").trainClass("KTX").trainNo(101)
						.departureTime("0900").arrivalTime("1140").wasteTime("0240").runDay("매일")
						.generalFare(59800).specialFare(83700).standingFare(50800)
						.build()));
		when(mocks.busScheduleProvider.findSchedule(any(), any())).thenReturn(List.of(
				TransitScheduleResDTO.BusSchedule.builder()
						.busClass(1).departureTime("0900").wasteTimeMin(260).fare(35300)
						.build()));
		when(mocks.flightScheduleProvider.findSchedule(any(), any())).thenReturn(List.of(
				TransitScheduleResDTO.FlightSchedule.builder()
						.airline("대한항공").flightNo("KE1401")
						.departureTime("0900").arrivalTime("1000").runDay("매일")
						.build()));

		return mocks;
	}

	/** 프로덕션 등록 순서(장소검색·도보·택시 → 기차·버스·항공 → 축제)대로 전체 tool을 구성한다. */
	private Object[] buildFullToolSet(ToolMocks mocks) {
		KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(mocks.placeQueryService);
		return new Object[]{
				new KakaoPlaceSearchTool(DESTINATION, mocks.placeQueryService, new CandidateCollector(), new KakaoPlaceCoordinateResolver(mocks.placeQueryService)),
				new WalkingRouteTool(DESTINATION, mocks.placeQueryService, resolver),
				new TaxiRouteTool(DESTINATION, mocks.placeQueryService, resolver),
				new TrainScheduleTool(mocks.trainScheduleProvider),
				new BusScheduleTool(mocks.busScheduleProvider),
				new FlightScheduleTool(mocks.flightScheduleProvider),
				new FestivalRecommendationTool(
						RegionCode.findByName(DESTINATION).orElseThrow(), TRIP_START, TRIP_END, mocks.festivalQueryService, new CandidateCollector())
		};
	}

	private AnthropicChatModel buildGmsChatModel(String apiKey) {
		// 프로덕션과 동일하게 web_search 서버tool을 주입해, web_search 등장 후에도 클라이언트 tool 선택이
		// 유지되는지(회귀) 함께 검증한다.
		AnthropicApi anthropicApi = AnthropicApi.builder()
				.baseUrl("https://gms.ssafy.io/gmsapi/api.anthropic.com")
				.apiKey(apiKey)
				.restClientBuilder(RestClient.builder()
						.requestInterceptor(new com.ssafy.ieumgil.domain.chatbot.config.WebSearchInterceptor()))
				.build();

		return AnthropicChatModel.builder()
				.anthropicApi(anthropicApi)
				.defaultOptions(AnthropicChatOptions.builder()
						.model("claude-haiku-4-5-20251001")
						.maxTokens(1024)
						.build())
				.build();
	}

	private String readGmsApiKeyFromDotenv() throws IOException {
		Path envFile = Path.of(".env");
		if (!Files.exists(envFile)) {
			return null;
		}
		Properties props = new Properties();
		try (var reader = Files.newBufferedReader(envFile)) {
			props.load(reader);
		}
		return props.getProperty("GMS_API_KEY");
	}
}
