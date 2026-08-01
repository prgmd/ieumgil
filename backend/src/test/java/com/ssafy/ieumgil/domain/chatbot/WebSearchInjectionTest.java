package com.ssafy.ieumgil.domain.chatbot;

import com.ssafy.ieumgil.domain.chatbot.config.WebSearchInterceptor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 프로덕션 {@link WebSearchInterceptor}가 실제 GMS haiku 경로에서 web_search 서버tool을 주입하고
 * 그 결과를 Spring AI가 파싱 가능한 형태로 정규화해 최종 답변까지 반환하는지 검증하는 통합 테스트.
 * GMS_API_KEY(.env)로만 게이팅하는 실모델 호출이라 비-CI.
 */
class WebSearchInjectionTest {

    @Test
    void webSearchAnswerComesThroughSpringAi() throws IOException {
        String apiKey = readGmsApiKeyFromDotenv();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "GMS_API_KEY 없음 — .env 확인 필요");

        AnthropicApi api = AnthropicApi.builder()
                .baseUrl("https://gms.ssafy.io/gmsapi/api.anthropic.com")
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestInterceptor(new WebSearchInterceptor()))
                .build();

        AnthropicChatModel model = AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(AnthropicChatOptions.builder()
                        .model("claude-haiku-4-5-20251001")
                        .maxTokens(1024)
                        .build())
                .build();

        String system = "당신은 '이음길' 여행 챗봇 '이음이'입니다. 답변은 한국어로 간결하게 하세요. "
                + "가게 영업 여부·리뷰·최신 상태처럼 확인이 필요한 정보는 웹 검색으로 확인해 답하세요.";

        String reply = ChatClient.builder(model).build()
                .prompt()
                .system(system)
                .user("산본 얼룩말식당 아직 운영중이야? 폐업 안 했어?")
                .call()
                .content();

        System.out.println("=== WEB SEARCH INJECTION REPLY ===");
        System.out.println(reply);
        System.out.println("=== END ===");

        // 여러 text 블록 병합이 동작하면 검색 사실이 담긴 완전한 답변이 온다(첫 블록 "네," 만 오면 실패).
        assertThat(reply).isNotBlank();
        assertThat(reply.length()).isGreaterThan(30);
        assertThat(reply.contains("운영") || reply.contains("영업") || reply.contains("폐업"))
                .as("web_search 결과가 담긴 완전한 답변이어야 함").isTrue();
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
