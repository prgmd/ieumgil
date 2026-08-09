package com.ssafy.ieumgil.domain.chatbot;

import com.ssafy.ieumgil.domain.chatbot.config.WebSearchInterceptor;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.service.ChatbotPrompt;
import com.ssafy.ieumgil.domain.chatbot.service.TripContextBuilder;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateSelector;
import com.ssafy.ieumgil.domain.chatbot.tool.PlaceRanker;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 지도 기반 추천의 <b>블록화 불변식</b> 게이트 (BOT-03).
 *
 * <p>지도 기반 추천의 존재 이유는 "드래그해서 일정 블록이 되는 것"이다. 추천 문장이
 * 아무리 좋아도 카드가 안 나오면 기능 자체가 무의미하다. 이 파일은 랭킹 품질을 보지 않고
 * <b>카드가 반드시 나오는가</b>만 본다.
 *
 * <p>실제로 카드가 사라진 전례가 있다 — 모델이 뷰포트 tool을 아예 부르지 않고 직전 턴
 * 이력만으로 산문 답을 내면 후보가 0건이 된다. 이는 web_search 주입 여부와 무관하게
 * 관측됐고(주입이 없던 E2E 실행에서도 발생), 프롬프트로 막는다. 단위 테스트는 mock
 * 경계에서 끝나 이 현상을 못 잡는다.
 *
 * <p>모델 출력은 확률적이므로 {@link #REPETITIONS}회 반복해 성공률을 측정한다.
 * 랭킹 변경 전후로 이 수치를 비교하는 것이 이 테스트의 핵심 용도다.
 */
@Tag("live")
class MapBlockabilityLiveTest {

    /** 해운대 일대 */
    private static final ChatbotReqDTO.MapContext VIEWPORT =
            new ChatbotReqDTO.MapContext(35.155, 129.155, 35.165, 129.170);

    /** 모델 출력이 확률적이라 1회로는 판단할 수 없다. 크레딧과 시간을 감안한 타협값 */
    private static final int REPETITIONS = 3;

    /** 키워드가 그대로 안 통하는 자유 문장 — 지도 모드의 실제 사용 양상이다 */
    private static final List<String> QUERIES = List.of(
            "조용히 앉아서 커피 마실 데 있을까?",
            "이 근처 카페 추천해줘",
            "여기서 밥 먹을 데 알려줘");

    private static final List<PlaceResDTO.Place> KAKAO_RESULTS = List.of(
            place("1", "블랙업커피 해운대점", 35.1633, 129.1643, "카페"),
            place("2", "동백섬 로스터리", 35.1590, 129.1600, "카페"),
            place("3", "해운대 암소갈비집", 35.1620, 129.1655, "음식점"),
            // 카페·식사 질문 어느 쪽에도 자연스럽게 안 걸리는 딴 주제 — 필터가 실제로 좁히는지
            // 관찰하려면 "검색은 됐지만 답변엔 안 나오는" 결과가 하나는 있어야 한다
            place("4", "해운대 게스트하우스", 35.1580, 129.1650, "숙박"));

    private static PlaceResDTO.Place place(String id, String name, double lat, double lng, String category) {
        return PlaceResDTO.Place.builder()
                .placeId(id).name(name).address("부산 해운대구")
                .lat(lat).lng(lng).category(category).categoryCode("CE7")
                .build();
    }

    @Test
    @DisplayName("I1·I3·I4·I5 — 검색 결과가 있으면 좌표를 갖춘 카드가 뷰포트 안에서 반드시 나온다")
    void alwaysProducesBlockableCards() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        int attempts = 0;
        int blockable = 0;

        for (String query : QUERIES) {
            for (int i = 0; i < REPETITIONS; i++) {
                attempts++;
                Result result = ask(apiKey, query);

                System.out.printf("[%s] 시도 %d — 카드 %d개%n", query, i + 1, result.candidates().size());
                // 응답 문구를 눈으로 검수하기 위해 항상 찍는다 — 근거 없는 표현("리뷰가 좋",
                // "평점", "인기")이 섞이는지는 카드 수만 봐서는 알 수 없다
                System.out.println("  응답: " + result.reply());

                if (result.candidates().isEmpty()) {
                    System.out.println("  ⚠️ 카드 0개");
                    continue;
                }
                blockable++;

                // I3 — 좌표가 없으면 프론트가 블록 생성에서 BLOCK400 을 맞는다
                assertThat(result.candidates())
                        .allSatisfy(c -> {
                            assertThat(c.lat()).as("카드 위도").isNotNull();
                            assertThat(c.lng()).as("카드 경도").isNotNull();
                        });

                // I4 — 지도 모드의 전제. 뷰포트 밖이면 사용자가 보고 있지 않은 곳이다
                assertThat(result.candidates()).allSatisfy(c -> {
                    assertThat(c.lat()).isBetween(VIEWPORT.swLat(), VIEWPORT.neLat());
                    assertThat(c.lng()).isBetween(VIEWPORT.swLng(), VIEWPORT.neLng());
                });

                // I5 — 카드는 카카오가 준 결과에서만 나온다. 웹에서 읽은 장소가 섞이면 안 된다
                List<String> allowedIds = KAKAO_RESULTS.stream().map(PlaceResDTO.Place::placeId).toList();
                assertThat(result.candidates())
                        .extracting(ChatbotResDTO.Candidate::placeId)
                        .allMatch(allowedIds::contains);
            }
        }

        System.out.printf("%n=== 블록화 성공률: %d/%d ===%n", blockable, attempts);

        // I1 — 전부 성공해야 한다. 한 번이라도 0건이면 사용자는 그 순간 아무것도 담을 수 없다
        assertThat(blockable)
                .as("카드가 나온 횟수 (0건이면 블록화 불가 — 기능이 죽은 것과 같다)")
                .isEqualTo(attempts);
    }

    @Test
    @DisplayName("I2 — 답변이 언급한 장소는 카드로도 담을 수 있다")
    void mentionedPlacesAreBlockable() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        Result result = ask(apiKey, "이 근처 카페 추천해줘");

        System.out.println("=== I2 응답 ===\n" + result.reply());

        List<String> cardNames = result.candidates().stream()
                .map(ChatbotResDTO.Candidate::name)
                .toList();

        // 답변에 등장한 카카오 장소는 전부 카드에 있어야 한다.
        // 이 검사는 KAKAO_RESULTS에 있는(우리가 아는) 이름만 본다 — 모델이 완전히
        // 지어낸 이름을 답변에 쓰고 그 이름의 카드가 없는 경우는 이 게이트가 못 잡는
        // 알려진 사각지대다. I5는 카드 쪽만 보고 응답 텍스트는 보지 않으므로 그 케이스를
        // 대신 잡아주지 않는다.
        List<String> mentionedButNotBlockable = KAKAO_RESULTS.stream()
                .map(PlaceResDTO.Place::name)
                .filter(name -> mentionsPlace(result.reply(), name))
                .filter(name -> !cardNames.contains(name))
                .toList();

        assertThat(mentionedButNotBlockable)
                .as("답변에 나왔는데 카드가 없는 장소 — 사용자가 읽은 곳을 담을 수 없다")
                .isEmpty();

        // 모델이 tool 결과를 실제로 썼다는 증거. 하나도 언급 안 하면 기억으로 답한 것이다
        assertThat(result.reply())
                .as("답변이 검색 결과를 반영하는가")
                .satisfiesAnyOf(
                        r -> assertThat(r).contains("블랙업커피"),
                        r -> assertThat(r).contains("동백섬"),
                        r -> assertThat(r).contains("암소갈비"));
    }

    /**
     * 전체 명칭 일치만으로는 "블랙업커피 해운대점"을 "블랙업커피"로 축약해 부른 경우나,
     * "해운대 암소갈비집"을 "암소갈비집"으로 앞쪽 지역어를 생략해 부른 경우를 놓친다.
     * 지점명(마지막 토큰이 "~점"으로 끝나는 부분)을 뗀 축약형과, 마지막 토큰 단독 언급을
     * 함께 확인해 그런 정상적인 축약 언급까지 "언급됨"으로 잡는다.
     *
     * <p>이 오라클은 {@code CandidateSelector}보다 관대하게 유지한다 — 의도된 비대칭이다.
     * 오라클을 프로덕션 매칭만큼 엄격하게 만들면, 오라클과 프로덕션이 같은 이유로 같은
     * 이름을 놓쳐 이 게이트가 정작 잡아야 할 실패(프로덕션이 못 찾는 정상 표현)를 가려버린다.
     */
    private static boolean mentionsPlace(String reply, String placeName) {
        String normalizedReply = reply.strip();
        String normalizedName = placeName.strip();
        if (normalizedReply.contains(normalizedName)) {
            return true;
        }
        String withoutBranchSuffix = stripBranchSuffix(normalizedName);
        if (!withoutBranchSuffix.equals(normalizedName) && normalizedReply.contains(withoutBranchSuffix)) {
            return true;
        }
        String lastToken = lastToken(normalizedName);
        return lastToken.length() >= 3 && normalizedReply.contains(lastToken);
    }

    private static String lastToken(String name) {
        int lastSpace = name.lastIndexOf(' ');
        return lastSpace >= 0 ? name.substring(lastSpace + 1) : name;
    }

    private static String stripBranchSuffix(String name) {
        int lastSpace = name.lastIndexOf(' ');
        if (lastSpace > 0 && name.endsWith("점")) {
            return name.substring(0, lastSpace);
        }
        return name;
    }

    private record Result(String reply, List<ChatbotResDTO.Candidate> candidates) {
    }

    private Result ask(String apiKey, String userMessage) {
        PlaceQueryService placeQueryService = mock(PlaceQueryService.class);
        when(placeQueryService.searchPlacesInRect(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(KAKAO_RESULTS);

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
                        new UserMessage(userMessage))))
                .tools(new ViewportPlaceSearchTool(
                        VIEWPORT, placeQueryService, collector,
                        new PlaceRanker.RankingContext(List.of(),
                                new PlaceRanker.Anchor(35.160, 129.1625, null), List.of())))
                .call()
                .content();

        // 프로덕션(ChatbotCommandServiceImpl)과 동일하게 조립한다 — collector 원본을 그대로 쓰면
        // 이 게이트가 CandidateSelector를 한 번도 안 거치고 통과해버린다
        String safeReply = reply == null ? "" : reply;
        List<ChatbotResDTO.Candidate> candidates =
                CandidateSelector.mentionedIn(safeReply, collector.candidates());
        return new Result(safeReply, candidates);
    }

    private AnthropicChatModel buildGmsChatModel(String apiKey) {
        // 프로덕션과 동일하게 인터셉터를 단다 — web_search 주입 여부가 카드 생성에 직접 영향을 준다
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
