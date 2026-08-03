package com.ssafy.ieumgil.domain.chatbot;

import com.ssafy.ieumgil.domain.chatbot.service.ChatbotPrompt;
import com.ssafy.ieumgil.domain.chatbot.service.TripContextBuilder;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceCoordinateResolver;
import com.ssafy.ieumgil.domain.chatbot.tool.KakaoPlaceSearchTool;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대표 음식 → 식당 추천의 전 과정을 실모델로 확인한다.
 *
 * <p>"전주에서 꼭 먹어야 하는 음식이 뭐야? 그 음식 먹을 수 있는 식당 추천해줘" 한 문장에
 * 두 단계가 들어 있다 — (1) 지역 대표 음식이 무엇인지 판단하고 (2) 그것을 검색어로 삼아
 * 장소를 찾는다. 우리 코드에는 (1)에 해당하는 로직이 없고 모델이 학습 지식으로 채운다.
 * 그 판단이 실제로 되는지, 그리고 검색 결과 중 일부를 골라 추천으로 정리하는지 본다.
 *
 * <p>카카오 호출은 mock으로 막고 실제에 가까운 전주 식당 데이터를 넣는다. web_search는
 * 프로덕션에서 인터셉터로 주입되지만 이 테스트는 그 경로를 타지 않으므로 비활성이다 —
 * 리뷰 기반 선별은 검증 대상이 아니다.
 */
@Tag("live")
class LocalSpecialtyRecommendationLiveTest {

    /** 전주 한옥마을·객사 일대 실제 상권을 참고한 후보 8곳 */
    private static final List<PlaceResDTO.Place> JEONJU_RESTAURANTS = List.of(
            place("1", "가족회관", "전북 전주시 완산구 전동3가 1", 35.8140, 127.1489, "음식점"),
            place("2", "고궁수라간", "전북 전주시 완산구 태조로 15", 35.8155, 127.1502, "음식점"),
            place("3", "한국집", "전북 전주시 완산구 어진길 119", 35.8161, 127.1478, "음식점"),
            place("4", "종로회관", "전북 전주시 완산구 전동 은행로 33", 35.8133, 127.1461, "음식점"),
            place("5", "왱이콩나물국밥", "전북 전주시 완산구 동문길 88", 35.8189, 127.1443, "음식점"),
            place("6", "삼백집", "전북 전주시 완산구 전주객사3길 22", 35.8202, 127.1401, "음식점"),
            place("7", "베테랑칼국수", "전북 전주시 완산구 경기전길 135", 35.8151, 127.1519, "음식점"),
            place("8", "조점례남문피순대", "전북 전주시 완산구 전동 풍남문2길 63", 35.8118, 127.1437, "음식점")
    );

    private static PlaceResDTO.Place place(String id, String name, String address,
                                          double lat, double lng, String category) {
        return PlaceResDTO.Place.builder()
                .placeId(id).name(name).address(address).lat(lat).lng(lng).category(category)
                .build();
    }

    @Test
    @DisplayName("대표 음식을 스스로 도출해 검색하고, 결과 중 일부를 골라 추천으로 정리한다")
    void derivesLocalSpecialtyThenRecommendsSubsetOfSearchResults() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
        when(placeQueryService.searchPlaces(anyString(), any(), any())).thenReturn(JEONJU_RESTAURANTS);

        Project project = Project.builder()
                .destination("전주")
                .startDate(LocalDate.of(2026, 10, 1))
                .endDate(LocalDate.of(2026, 10, 3))
                .budgetHeadcount(2)
                .transportPref(TransportPref.PUBLIC)
                .build();
        String systemPrompt = ChatbotPrompt.SYSTEM
                + ChatbotPrompt.modeTail(ChatbotMode.GENERAL)
                + TripContextBuilder.build(project, 2);

        CandidateCollector collector = new CandidateCollector();
        String reply = ChatClient.builder(buildGmsChatModel(apiKey)).build()
                .prompt(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage("전주에서 꼭 먹어야하는 음식이 뭐야? 그 음식 먹을 수 있는 식당 추천해줘."))))
                .tools(new KakaoPlaceSearchTool("전주", placeQueryService, collector, new KakaoPlaceCoordinateResolver(placeQueryService)))
                .call()
                .content();

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(placeQueryService, atLeastOnce()).searchPlaces(queryCaptor.capture(), any(), any());
        List<String> queries = queryCaptor.getAllValues();

        System.out.println("=== 모델이 만든 검색어 ===");
        queries.forEach(q -> System.out.println("  " + q));
        System.out.println("=== 응답 ===\n" + reply);
        System.out.println("=== 수집된 후보 " + collector.candidates().size() + "개 ===");
        collector.candidates().forEach(c ->
                System.out.println("  " + c.name() + " | " + c.category() + " | " + c.lat() + "," + c.lng()));
        System.out.println("==========================");

        // 1) 검색어를 대표 음식으로 특정했다 — 사용자 문장에는 음식 이름이 없다
        assertThat(queries).anySatisfy(q ->
                assertThat(q).containsAnyOf("비빔밥", "콩나물국밥", "한정식", "국밥", "칼국수", "피순대"));
        // 2) 목적지가 검색어에 붙는다(tool 계약)
        assertThat(queries).allSatisfy(q -> assertThat(q).startsWith("전주 "));
        // 3) 검색된 장소가 블록화 가능한 형태로 수집됐다
        assertThat(collector.candidates()).isNotEmpty();
        assertThat(collector.candidates()).allSatisfy(c -> {
            assertThat(c.lat()).isNotNull();
            assertThat(c.lng()).isNotNull();
            assertThat(c.placeId()).isNotBlank();
        });
        // 4) 응답이 검색 결과 안의 식당을 실제로 언급한다(환각 아님)
        long mentioned = JEONJU_RESTAURANTS.stream()
                .filter(p -> reply.contains(p.name()))
                .count();
        assertThat(mentioned).isGreaterThanOrEqualTo(3L);
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
                        .maxTokens(1500)
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
