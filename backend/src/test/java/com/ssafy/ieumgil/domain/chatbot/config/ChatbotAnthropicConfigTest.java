package com.ssafy.ieumgil.domain.chatbot.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GMS 호출에 타임아웃이 걸려 있는지 못을 박는다.
 *
 * <p>{@code application.yaml}의 {@code spring.http.client.*}는 Boot 자동설정 Builder에만 붙으므로,
 * {@code RestClient.builder()}를 직접 만들어 쓰는 이 설정에는 <b>적용되지 않는다</b>. 카카오·ODsay·
 * TourAPI는 타임아웃이 있는데 가장 느린 GMS만 무한 대기하면, 톰캣 워커가 물려 챗봇이 아니라
 * 서비스 전체가 멎는다.
 */
class ChatbotAnthropicConfigTest {

    @Test
    @DisplayName("GMS 호출에 connect/read 타임아웃이 명시돼 있다")
    void gmsCallHasExplicitTimeouts() {
        assertThat(ChatbotAnthropicConfig.GMS_TIMEOUTS.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(ChatbotAnthropicConfig.GMS_TIMEOUTS.readTimeout()).isEqualTo(Duration.ofSeconds(60));
    }
}
