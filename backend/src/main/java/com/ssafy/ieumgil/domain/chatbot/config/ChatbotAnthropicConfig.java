package com.ssafy.ieumgil.domain.chatbot.config;

import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 챗봇용 AnthropicApi 빈. Spring AI 자동설정의 anthropicApi 빈은 {@code @ConditionalOnMissingBean}이라
 * 이 빈이 있으면 물러난다. 여기에 {@link WebSearchInterceptor}를 달아 챗봇 요청에 web_search 서버tool을
 * 주입한다. 애플리케이션에서 ChatModel 소비자는 챗봇뿐이라 사실상 챗봇 전용이다.
 */
@Configuration
public class ChatbotAnthropicConfig {

    @Bean
    public AnthropicApi anthropicApi(@Value("${spring.ai.anthropic.base-url}") String baseUrl,
                                     @Value("${spring.ai.anthropic.api-key}") String apiKey) {
        return AnthropicApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder().requestInterceptor(new WebSearchInterceptor()))
                .build();
    }
}
