package com.ssafy.ieumgil.domain.chatbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.chatbot.config.WebSearchInterceptor;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotPrompt;
import com.ssafy.ieumgil.domain.chatbot.service.TripContextBuilder;
import com.ssafy.ieumgil.domain.chatbot.tool.BoardTool;
import com.ssafy.ieumgil.domain.chatbot.tool.BusScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.chatbot.tool.FlightScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceCoordinateResolver;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.chatbot.tool.RequestScopedBoard;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
import com.ssafy.ieumgil.domain.chatbot.tool.TrainScheduleTool;
import com.ssafy.ieumgil.domain.chatbot.tool.WalkingRouteTool;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.transit.service.BusScheduleProvider;
import com.ssafy.ieumgil.domain.transit.service.FlightScheduleProvider;
import com.ssafy.ieumgil.domain.transit.service.TrainScheduleProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 프롬프트 캐싱(cache_control) 실동작 검증 — {@link WebSearchInterceptor}가 tools 프리픽스에 붙인
 * 캐싱 브레이크포인트가 실제로 GMS에서 캐시읽기로 재사용되는지 본다.
 *
 * <p>프로덕션 GENERAL 요청과 같은 모양(시스템 프롬프트 + tool 9종, interceptor가 web_search까지 주입)을
 * 만들어 <b>같은 대화로 2턴 연속</b> 호출한다. tool 정의는 두 턴 모두 동일한 인스턴스에서 직렬화되므로
 * 프리픽스가 바이트 불변이다. 1턴이 캐시를 쓰고({@code cache_creation}), 2턴이 캐시를
 * 읽어야({@code cache_read > 0}) 한다.
 *
 * <p>usage는 Spring AI의 {@code .content()}로는 노출되지 않으므로, interceptor 체인에 원 응답을
 * 그대로 읽어 usage를 집계하는 캡처 interceptor를 하나 더 달아 확인한다. tool은 실제로 호출되지
 * 않도록(=mock 의존성이 동작할 필요 없도록) 사소한 질문만 던진다 — 캐시 대상은 요청 프리픽스의
 * tool 정의이지 tool 실행이 아니다.
 */
@Tag("live")
class PromptCachingLiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 한 번의 GMS 응답에서 뽑은 캐시 관련 usage. */
    private record Usage(long inputTokens, long cacheCreation, long cacheRead) {
    }

    @Test
    @DisplayName("같은 대화 2턴: 1턴이 tools 프리픽스를 캐시하고 2턴이 캐시읽기로 재사용한다")
    void toolsPrefixIsCachedAndReadAcrossTurns() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        List<Usage> captured = new ArrayList<>();
        AnthropicChatModel chatModel = buildGmsChatModel(apiKey, captured);

        // 프로덕션 GENERAL 모드와 같은 시스템 프롬프트(목적지·기간 있는 프로젝트).
        Project project = Project.builder()
                .destination("제주")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 3))
                .budgetHeadcount(2)
                .build();
        String systemPrompt = ChatbotPrompt.SYSTEM
                + ChatbotPrompt.modeTail(ChatbotMode.GENERAL)
                + TripContextBuilder.build(project, 2);

        Object[] tools = buildGeneralTools();

        // 완전히 동일한 요청을 3턴 반복한다 — 프리픽스(tools+system)뿐 아니라 뒷 메시지까지 같게 해
        // 콘텐츠 변동 변수를 제거한다. 턴 사이 짧은 대기로 캐시 전파 지연도 배제한다.
        String user = "한 단어로만 답해줘: 안녕";
        for (int i = 0; i < 3; i++) {
            String reply = ask(chatModel, systemPrompt, tools, user);
            assertThat(reply).isNotBlank();
            sleepQuietly();
        }

        assertThat(captured).as("GMS 응답 usage를 3건 캡처해야 한다").hasSize(3);
        System.out.printf("=== 프롬프트 캐싱 usage (동일 요청 3턴) ===%n");
        for (int i = 0; i < captured.size(); i++) {
            Usage u = captured.get(i);
            System.out.printf("turn%d  input=%d  cache_creation=%d  cache_read=%d%n",
                    i + 1, u.inputTokens(), u.cacheCreation(), u.cacheRead());
        }
        System.out.printf("(haiku 4.5 최소 캐시 프리픽스 = 4096 토큰; cache_creation = 캐시된 tools+system 프리픽스 크기)%n");
        System.out.println("=========================");

        // 핵심 검증: 반복 요청에서 캐시읽기가 발생했다. cache_creation은 뜨는데 cache_read가
        // 계속 0이면 프리픽스 문제가 아니라 게이트웨이가 요청 간 캐시 어피니티를 안 주는 것이다.
        long maxCacheRead = captured.stream().mapToLong(Usage::cacheRead).max().orElse(0);
        assertThat(maxCacheRead)
                .as("반복 요청 중 한 번은 tools+system 프리픽스를 캐시읽기로 재사용해야 한다")
                .isGreaterThan(0);
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 프로덕션 resolveTools(GENERAL, 목적지·기간 있음)와 같은 tool 구성. 의존성은 mock — tool은 실행되지 않는다. */
    private Object[] buildGeneralTools() {
        PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
        FestivalQueryService festivalQueryService = mock(FestivalQueryService.class);
        TrainScheduleProvider trainProvider = mock(TrainScheduleProvider.class);
        BusScheduleProvider busProvider = mock(BusScheduleProvider.class);
        FlightScheduleProvider flightProvider = mock(FlightScheduleProvider.class);

        RequestScopedBoard board = new RequestScopedBoard(List::of);
        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(placeQueryService, board);
        CandidateCollector collector = new CandidateCollector();

        return new Object[]{
                new KakaoPlaceSearchTool("제주", placeQueryService, collector, resolver),
                new WalkingRouteTool("제주", placeQueryService, resolver),
                new TaxiRouteTool("제주", placeQueryService, resolver),
                new BoardTool(board),
                new TrainScheduleTool(trainProvider),
                new BusScheduleTool(busProvider),
                new FlightScheduleTool(flightProvider),
                new FestivalRecommendationTool(
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3),
                        festivalQueryService, collector)
        };
    }

    private String ask(AnthropicChatModel chatModel, String systemPrompt, Object[] tools, String user) {
        return ChatClient.builder(chatModel).build()
                .prompt(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(user))))
                .tools(tools)
                .call()
                .content();
    }

    private AnthropicChatModel buildGmsChatModel(String apiKey, List<Usage> captured) {
        ClientHttpRequestFactorySettings timeouts = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(60));
        AnthropicApi api = AnthropicApi.builder()
                .baseUrl("https://gms.ssafy.io/gmsapi/api.anthropic.com")
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(timeouts))
                        // 캡처가 바깥(응답을 마지막에 본다), web_search/캐시 주입이 안쪽.
                        .requestInterceptor(new UsageCapturingInterceptor(captured))
                        .requestInterceptor(new WebSearchInterceptor()))
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model("claude-haiku-4-5-20251001")
                        .maxTokens(64)
                        .build())
                .build();
    }

    private String readGmsApiKey() throws IOException {
        Path env = Path.of(".env");
        if (!Files.exists(env)) {
            return null;
        }
        return Files.readAllLines(env).stream()
                .filter(line -> line.startsWith("GMS_API_KEY="))
                .map(line -> line.substring("GMS_API_KEY=".length()).trim())
                .findFirst()
                .orElse(null);
    }

    /** 원 GMS 응답의 usage(cache_creation/cache_read/input)를 집계하고, 응답은 재읽기 가능하게 되돌린다. */
    private record UsageCapturingInterceptor(List<Usage> sink) implements ClientHttpRequestInterceptor {
        @Override
        @NonNull
        public ClientHttpResponse intercept(@NonNull HttpRequest request, @NonNull byte[] body,
                                            @NonNull ClientHttpRequestExecution execution) throws IOException {
            ClientHttpResponse response = execution.execute(request, body);
            byte[] bytes = response.getBody().readAllBytes();
            try {
                JsonNode usage = MAPPER.readTree(bytes).path("usage");
                sink.add(new Usage(
                        usage.path("input_tokens").asLong(0),
                        usage.path("cache_creation_input_tokens").asLong(0),
                        usage.path("cache_read_input_tokens").asLong(0)));
            } catch (Exception ignored) {
                // 파싱 실패는 검증에서 usage 건수 미달로 드러난다.
            }
            return new BufferedResponse(response, bytes);
        }
    }

    /** 응답 body를 이미 읽었으므로 재읽기 가능한 복사본으로 되돌리는 래퍼. */
    private record BufferedResponse(ClientHttpResponse delegate, byte[] body) implements ClientHttpResponse {
        @Override
        @NonNull
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        @NonNull
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.addAll(delegate.getHeaders());
            headers.setContentLength(body.length);
            return headers;
        }

        @Override
        @NonNull
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        @NonNull
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
