package com.ssafy.ieumgil.domain.chatbot.dto;

import com.ssafy.ieumgil.domain.chatbot.ChatbotMode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChatbotReqDTOTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void blankMessageFailsValidation() {
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage("", null, null);

        Set<ConstraintViolation<ChatbotReqDTO.SendMessage>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void tooLongMessageFailsValidation() {
        String longMessage = "a".repeat(2001);
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage(longMessage, null, null);

        Set<ConstraintViolation<ChatbotReqDTO.SendMessage>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void validRequestPassesValidation() {
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage("안녕", null, null);

        Set<ConstraintViolation<ChatbotReqDTO.SendMessage>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void omittedModeDefaultsToGeneral() {
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage("안녕", null, null);

        assertThat(request.modeOrDefault()).isEqualTo(ChatbotMode.GENERAL);
    }

    @Test
    void explicitModeIsKept() {
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage("안녕", ChatbotMode.MAP, null);

        assertThat(request.modeOrDefault()).isEqualTo(ChatbotMode.MAP);
    }
}
