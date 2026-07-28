package com.ssafy.ieumgil.domain.chatbot.dto;

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
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage("");

        Set<ConstraintViolation<ChatbotReqDTO.SendMessage>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void tooLongMessageFailsValidation() {
        String longMessage = "a".repeat(2001);
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage(longMessage);

        Set<ConstraintViolation<ChatbotReqDTO.SendMessage>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void validRequestPassesValidation() {
        ChatbotReqDTO.SendMessage request = new ChatbotReqDTO.SendMessage("안녕");

        Set<ConstraintViolation<ChatbotReqDTO.SendMessage>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }
}
