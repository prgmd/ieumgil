package com.ssafy.ieumgil.domain.chatbot.config;

import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 챗봇용 AnthropicApi 빈. Spring AI 자동설정의 anthropicApi 빈은 {@code @ConditionalOnMissingBean}이라
 * 이 빈이 있으면 물러난다. 여기에 {@link WebSearchInterceptor}를 달아 챗봇 요청에 web_search 서버tool을
 * 주입한다. 애플리케이션에서 ChatModel 소비자는 챗봇뿐이라 사실상 챗봇 전용이다.
 */
@Configuration
public class ChatbotAnthropicConfig {

    /**
     * GMS 호출 타임아웃. <b>여기서 직접 걸어야 한다</b> — {@code application.yaml}의
     * {@code spring.http.client.*}는 Boot 자동설정 Builder에만 붙으므로 아래처럼
     * {@code RestClient.builder()}를 직접 만들면 적용되지 않는다(같은 함정을
     * {@code RestClientHttpConfig} 주석이 경고하고 있다). 타임아웃이 없으면 GMS가 응답하지 않을 때
     * 톰캣 워커가 무기한 물려 챗봇이 아니라 서비스 전체가 멎는다.
     *
     * <p>connect 5초는 다른 외부 API와 같은 값이다(연결은 느릴 이유가 없다). read는 60초로
     * 넉넉히 잡는다 — LLM은 토큰을 다 만들 때까지 응답이 오지 않아 max-tokens 1024에도
     * 수십 초가 걸릴 수 있고, 여기서 끊기면 크레딧만 쓰고 답을 못 받는다.
     */
    static final ClientHttpRequestFactorySettings GMS_TIMEOUTS = ClientHttpRequestFactorySettings.defaults()
            .withConnectTimeout(Duration.ofSeconds(5))
            .withReadTimeout(Duration.ofSeconds(60));

    @Bean
    public AnthropicApi anthropicApi(@Value("${spring.ai.anthropic.base-url}") String baseUrl,
                                     @Value("${spring.ai.anthropic.api-key}") String apiKey) {
        return AnthropicApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(GMS_TIMEOUTS))
                        .requestInterceptor(new WebSearchInterceptor()))
                .build();
    }
}
