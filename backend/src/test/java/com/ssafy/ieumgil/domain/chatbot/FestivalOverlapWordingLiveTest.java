package com.ssafy.ieumgil.domain.chatbot;

import com.ssafy.ieumgil.domain.chatbot.service.ChatbotPrompt;
import com.ssafy.ieumgil.domain.chatbot.service.TripContextBuilder;
import com.ssafy.ieumgil.domain.chatbot.tool.CandidateCollector;
import com.ssafy.ieumgil.domain.chatbot.tool.FestivalRecommendationTool;
import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 축제 추천 응답이 여행 기간 겹침을 부풀리지 않는지 실모델로 확인한다.
 *
 * <p>2026-07-31 품질 평가에서 "여행 기간과 딱 맞아요", "기간 내내 즐길 수 있어요"처럼
 * 겹침 정도를 부풀리는 문제가 나왔다. 서버가 겹침 구간을 계산해 넘기는 것으로 고쳤고,
 * 이 테스트는 <b>가장 위험한 케이스</b>(4일 여행에 마지막 하루만 겹치는 축제)로 검증한다.
 *
 * <p>실모델을 호출하므로 비결정적이다. 문구 자체는 매번 다르므로 결정적인 사실만 단언한다 —
 * 실제 겹침 날짜가 나오는지, 전 기간을 뜻하는 표현이 없는지.
 */
@Tag("live")
class FestivalOverlapWordingLiveTest {

    private static final LocalDate TRIP_START = LocalDate.of(2026, 10, 1);
    private static final LocalDate TRIP_END = LocalDate.of(2026, 10, 4);

    @Test
    @DisplayName("마지막 하루만 겹치는 축제를 '기간 내내'로 부풀리지 않는다")
    void doesNotOverstateOverlapForSingleDayFestival() throws IOException {
        String apiKey = readGmsApiKey();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        FestivalQueryService festivalQueryService = mock(FestivalQueryService.class);
        // 여행 마지막 날에 시작해 여행이 끝난 뒤까지 이어지는 축제 → 겹침은 10-04 하루뿐
        Festival festival = Festival.builder()
                .contentId("1").title("부산 불꽃축제").category("EV01")
                .lDongRegnCd("26").addr("부산광역시 수영구")
                .lat(35.15).lng(129.11)
                .eventStartDate(TRIP_END)
                .eventEndDate(TRIP_END.plusDays(10))
                .build();
        when(festivalQueryService.findByRegionAndDateRange(any(), any(), any())).thenReturn(List.of(festival));

        Project project = Project.builder()
                .destination("부산").startDate(TRIP_START).endDate(TRIP_END).budgetHeadcount(4)
                .build();
        String systemPrompt = ChatbotPrompt.SYSTEM
                + ChatbotPrompt.modeTail(ChatbotMode.GENERAL)
                + TripContextBuilder.build(project, 4);

        String reply = ChatClient.builder(buildGmsChatModel(apiKey)).build()
                .prompt(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage("여행 기간에 즐길만한 축제 있어?"))))
                .tools(new FestivalRecommendationTool(
                        TRIP_START, TRIP_END, festivalQueryService, new CandidateCollector()))
                .call()
                .content();

        System.out.println("=== 실모델 응답 ===\n" + reply + "\n==================");

        // 1) 메타데이터가 프롬프트에 있어도 축제 tool을 건너뛰지 않는다
        verify(festivalQueryService).findByRegionAndDateRange(any(), any(), any());
        // 2) 실제 겹침 날짜를 말한다
        assertThat(reply).containsAnyOf("10월 4일", "10-04", "10/4", "4일");
        // 3) 전 기간을 뜻하는 부풀림 표현이 없다
        assertThat(reply).doesNotContain("내내").doesNotContain("딱 맞");
        // 4) 영어 라벨을 프롬프트에 썼지만 답변은 한국어다
        assertThat(reply).matches("(?s).*[가-힣].*");
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
}
