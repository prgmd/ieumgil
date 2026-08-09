package com.ssafy.ieumgil.domain.chatbot.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatbotExceptionTest {

    @Test
    void carriesGivenErrorCode() {
        ChatbotException exception = new ChatbotException(ChatbotErrorCode.GMS_CALL_FAILED);

        assertThat(exception.getCode()).isEqualTo(ChatbotErrorCode.GMS_CALL_FAILED);
        assertThat(exception.getMessage()).isEqualTo("GMS 응답을 받지 못했습니다.");
    }
}
