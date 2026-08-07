package com.ssafy.ieumgil.domain.chatbot;

import com.ssafy.ieumgil.domain.chatbot.config.WebSearchInterceptor;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotPrompt;
import com.ssafy.ieumgil.domain.chatbot.service.TripContextBuilder;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.ViewportPlaceSearchTool;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지도 기반 추천 모드가 실모델에서 끝까지 동작하는지 확인한다 (BOT-03).
 *
 * <p>단위 테스트는 전부 mock 경계에서 끝나므로 "모델이 자유 문장에서 검색어를 도출해
 * 뷰포트 tool을 실제로 호출하는지"는 검증되지 않는다. 이 테스트가 그 구간을 메꾼다.
 * 카카오 호출은 mock으로 막고 모델 판단만 본다.
 */
@Tag("live")
class MapModeLiveTest {

    private static final ChatbotReqDTO.MapContext HAEUNDAE_VIEWPORT =
            new ChatbotReqDTO.MapContext(35.155, 129.155, 35.165, 129.170);

    @Test
    @DisplayName("자유 문장에서 검색어를 도출해 뷰포트 tool을 호출하고, 범위 밖 장소를 지어내지 않는다")
    void modelDerivesKeywordAndCallsViewportTool() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(PlaceResDTO.Place.builder()
                        .placeId("1").name("블랙업커피 해운대점").address("부산 해운대구 중동2로 16")
                        .lat(35.1633).lng(129.1643).category("카페")
                        .build()));

        Project project = Project.builder()
                .destination("부산")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 4))
                .budgetHeadcount(4)
                .build();
        String systemPrompt = ChatbotPrompt.SYSTEM
                + ChatbotPrompt.modeTail(ChatbotMode.MAP)
                + TripContextBuilder.build(project, 4);

        CandidateCollector collector = new CandidateCollector();
        String reply = ChatClient.builder(buildGmsChatModel(apiKey)).build()
                .prompt(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage("조용히 앉아서 커피 마실 데 있을까?"))))
                .tools(new ViewportPlaceSearchTool(HAEUNDAE_VIEWPORT, placeQueryService, collector))
                .call()
                .content();

        System.out.println("=== MAP 모드 실모델 응답 ===\n" + reply + "\n==========================");

        // 1) 모델이 "커피 마실 데" 같은 자유 문장에서 검색어를 도출해 뷰포트 tool을 호출한다
        verify(placeQueryService).searchPlacesInRect(
                anyString(), eq(35.155), eq(129.155), eq(35.165), eq(129.170));
        // 2) 목적지 문자열 기반 일반 검색은 쓰지 않는다 (MAP 모드에는 그 tool이 없다)
        verify(placeQueryService, never()).searchPlaces(anyString(), anyDouble(), anyDouble());
        // 3) 검색된 장소가 후보로 수집된다
        assertThat(collector.candidates()).hasSize(1);
        assertThat(collector.candidates().get(0).placeId()).isEqualTo("1");
        // 4) 답변에 그 장소가 등장하고 한국어를 유지한다
        assertThat(reply).contains("블랙업커피");
        assertThat(reply).matches("(?s).*[가-힣].*");
    }

    @Test
    @DisplayName("직전에 찾던 대상이 있으면 '여기는 어때?'에 위치를 되묻지 않고 그 검색어로 다시 호출한다")
    void modelReusesPreviousKeyword_forHereRequest() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(PlaceResDTO.Place.builder()
                        .placeId("1").name("블랙업커피 해운대점").address("부산 해운대구 중동2로 16")
                        .lat(35.1633).lng(129.1643).category("카페")
                        .build()));

        Project project = Project.builder()
                .destination("부산")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 4))
                .budgetHeadcount(4)
                .build();
        String systemPrompt = ChatbotPrompt.SYSTEM
                + ChatbotPrompt.modeTail(ChatbotMode.MAP)
                + TripContextBuilder.build(project, 4);

        // 트레이스 재현: 앞 turn 에서 "조용한 카페"를 물었고 이음이가 지역을 되물은 흐름 뒤에
        // 사용자가 지도를 옮기고 "여기는 어때?"라고 한다. 되묻기를 반복하지 말고 직전 검색어
        // (조용한 카페)로 현재 뷰포트를 다시 검색해야 한다.
        CandidateCollector collector = new CandidateCollector();
        String reply = ChatClient.builder(buildGmsChatModel(apiKey)).build()
                .prompt(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage("조용한 카페 추천해줘"),
                        new AssistantMessage("어느 지역에서 찾을까요? 해운대, 남포동, 광안리 같은 위치를 알려주시면 찾아드릴게요!"),
                        new UserMessage("여기는 어때?"))))
                .tools(new ViewportPlaceSearchTool(HAEUNDAE_VIEWPORT, placeQueryService, collector))
                .call()
                .content();

        System.out.println("=== MAP 모드 '여기는 어때?' 응답 ===\n" + reply + "\n==========================");

        // 핵심 회귀 가드: 위치를 다시 되묻지 않고 직전 검색어로 뷰포트 tool 을 호출한다
        verify(placeQueryService).searchPlacesInRect(
                anyString(), eq(35.155), eq(129.155), eq(35.165), eq(129.170));
        verify(placeQueryService, never()).searchPlaces(anyString(), anyDouble(), anyDouble());
        assertThat(collector.candidates()).hasSize(1);
        assertThat(reply).contains("블랙업커피");
    }

    private AnthropicChatModel buildGmsChatModel(String apiKey) {
        // 프로덕션과 동일하게 WebSearchInterceptor를 단다 — MAP 모드에서 web_search가 주입되면
        // 모델이 뷰포트 tool 대신 산문 답을 내 카드가 사라지므로, interceptor의 MAP 스킵이 실제로
        // 동작하는지까지 이 경로로 검증한다.
        AnthropicApi api = AnthropicApi.builder()
                .baseUrl("https://gms.ssafy.io/gmsapi/api.anthropic.com")
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestInterceptor(new WebSearchInterceptor()))
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model("claude-haiku-4-5-20251001")
                        .maxTokens(1024)
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
}
