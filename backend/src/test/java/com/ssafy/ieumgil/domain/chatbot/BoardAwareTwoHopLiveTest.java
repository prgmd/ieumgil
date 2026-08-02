package com.ssafy.ieumgil.domain.chatbot;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotPrompt;
import com.ssafy.ieumgil.domain.chatbot.service.TripContextBuilder;
import com.ssafy.ieumgil.domain.chatbot.tool.BoardTool;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import org.mockito.ArgumentCaptor;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceCoordinateResolver;
import com.ssafy.ieumgil.domain.chatbot.tool.RequestScopedBoard;
import com.ssafy.ieumgil.domain.chatbot.tool.TaxiRouteTool;
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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보드 인식 tool의 진짜 관문 — 2홉이 실모델에서 성립하는지 본다.
 *
 * <p>"2번째 블록에서 3번째 블록까지 택시로 얼마나 나와?"는 보드 조회 → 택시 경로의 두 홉을
 * 요구한다. PLAN.md에 검증된 것은 단일홉 7/7이고 2홉은 미검증으로 기록돼 있다. haiku의
 * 멀티홉 신뢰도가 이 기능의 최대 리스크다.
 *
 * <p>보드 tool이 좌표를 함께 반환하는 것이 완화책이다. 좌표가 없으면 모델이 블록 이름으로
 * 카카오를 재검색해야 해 3홉이 된다. 그래서 이 테스트는 좌표를 그대로 썼는지도 확인한다 —
 * 이름으로 다시 검색했다면 설계 의도가 무너진 것이다.
 */
@Tag("live")
class BoardAwareTwoHopLiveTest {

    private static Block block(int dayNo, String orderKey, String name, double lat, double lng) {
        return Block.builder()
                .dayNo(dayNo).orderKey(orderKey).name(name)
                .category(BlockCategory.SPOT)
                .startTime(LocalTime.of(9, 0)).durationMin(90).budget(0)
                .lat(BigDecimal.valueOf(lat)).lng(BigDecimal.valueOf(lng))
                .source(BlockSource.KAKAO)
                .build();
    }

    @Test
    @DisplayName("보드를 읽어 2번째·3번째 블록 좌표로 택시 경로를 조회한다 (2홉)")
    void resolvesBlockPositionsThenQueriesTaxiRoute() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        // 제주 1일차 체인 3곳 — 2번째(성산일출봉) → 3번째(섭지코지)
        BlockRepository blockRepository = mock(BlockRepository.class);
        when(blockRepository.findChain(12L)).thenReturn(List.of(
                block(1, "a0", "제주공항", 33.5070, 126.4930),
                block(1, "a1", "성산일출봉", 33.4581, 126.9425),
                block(1, "a2", "섭지코지", 33.4249, 126.9310)
        ));

        PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
        when(placeQueryService.getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Optional.of(new PlaceResDTO.TaxiRoute(9800, 7200, 15)));

        Project project = Project.builder()
                .destination("제주")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 3))
                .budgetHeadcount(2)
                .build();
        String systemPrompt = ChatbotPrompt.SYSTEM
                + ChatbotPrompt.modeTail(ChatbotMode.GENERAL)
                + TripContextBuilder.build(project, 2);

        // 보드 공급자를 tool 둘이 공유한다 — 한 메시지에 findChain은 한 번만 나가야 한다.
        // 카카오 검색은 스텁하지 않는다. 보드 좌표만으로 성립해야 한다.
        RequestScopedBoard board = new RequestScopedBoard(() -> blockRepository.findChain(12L));
        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(placeQueryService, board);
        String reply = ChatClient.builder(buildGmsChatModel(apiKey)).build()
                .prompt(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage("1일차 2번째 일정에서 3번째 일정까지 택시로 가면 얼마나 나와?"))))
                .tools(
                        new BoardTool(board),
                        new TaxiRouteTool("제주", placeQueryService, resolver))
                .call()
                .content();

        System.out.println("=== 2홉 실모델 응답 ===\n" + reply + "\n=====================");

        // 1) 보드를 읽었다
        verify(blockRepository).findChain(12L);
        // 2) 택시 경로를 조회했다 — 두 번째 홉이 성립했다
        verify(placeQueryService).getTaxiRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        // 3) 보드가 준 좌표를 썼다 — 이름으로 카카오를 재검색했다면 설계 의도가 무너진 것이다
        verify(placeQueryService, never()).searchPlaces(anyString(), anyDouble(), anyDouble());
        // 4) 조회한 값이 답변에 실렸다
        assertThat(reply).containsAnyOf("9,800", "9800");
    }

    private AnthropicChatModel buildGmsChatModel(String apiKey) {
        AnthropicApi api = AnthropicApi.builder()
                .baseUrl("https://gms.ssafy.io/gmsapi/api.anthropic.com")
                .apiKey(apiKey)
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

    @Test
    @DisplayName("보드 블록을 기준으로 '근처' 검색을 한다 — 좌표는 보드에서 오고 카카오 이름 재검색이 없다")
    void searchesNearABoardBlock() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        BlockRepository blockRepository = mock(BlockRepository.class);
        when(blockRepository.findChain(12L)).thenReturn(List.of(
                block(1, "a0", "제주공항", 33.5070, 126.4930),
                block(1, "a1", "성산일출봉", 33.4581, 126.9425)
        ));

        PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
        when(placeQueryService.searchPlaces(anyString(), anyDouble(), anyDouble()))
                .thenReturn(List.of(PlaceResDTO.Place.builder()
                        .placeId("1").name("성산 바다뷰 카페").address("제주 서귀포시 성산읍")
                        .lat(33.4590).lng(126.9430).category("카페")
                        .build()));

        Project project = Project.builder()
                .destination("제주")
                .startDate(LocalDate.of(2026, 10, 1)).endDate(LocalDate.of(2026, 10, 3))
                .budgetHeadcount(2)
                .build();
        String systemPrompt = ChatbotPrompt.SYSTEM
                + ChatbotPrompt.modeTail(ChatbotMode.GENERAL)
                + TripContextBuilder.build(project, 2);

        RequestScopedBoard board = new RequestScopedBoard(() -> blockRepository.findChain(12L));
        KakaoPlaceCoordinateResolver resolver = new KakaoPlaceCoordinateResolver(placeQueryService, board);
        CandidateCollector collector = new CandidateCollector();

        String reply = ChatClient.builder(buildGmsChatModel(apiKey)).build()
                .prompt(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage("1일차 두 번째 일정 근처에 카페 있어?"))))
                .tools(
                        new BoardTool(board),
                        new KakaoPlaceSearchTool("제주", placeQueryService, collector, resolver))
                .call()
                .content();

        System.out.println("=== 근처 검색 실모델 응답 ===\n" + reply + "\n===========================");

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Double> latCaptor = ArgumentCaptor.forClass(Double.class);
        verify(placeQueryService).searchPlaces(queryCaptor.capture(), latCaptor.capture(), any());

        System.out.println("검색어=" + queryCaptor.getValue() + " / 기준위도=" + latCaptor.getValue());

        // 성산일출봉 좌표를 기준으로 검색했다 — 보드에서 온 값이다
        assertThat(latCaptor.getValue()).isEqualTo(33.4581);
        // 좌표가 범위를 좁히므로 목적지 접두사는 붙지 않는다
        assertThat(queryCaptor.getValue()).doesNotStartWith("제주 ");
        assertThat(reply).contains("성산 바다뷰 카페");
    }
}
