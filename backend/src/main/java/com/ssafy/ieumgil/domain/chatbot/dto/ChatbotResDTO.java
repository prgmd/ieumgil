package com.ssafy.ieumgil.domain.chatbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

public class ChatbotResDTO {

    @Builder
    @Schema(description = "챗봇 응답")
    public record MessageResult(
            @Schema(description = "챗봇 응답 메시지", example = "제주도 2박3일이라면 성산일출봉을 추천해요.")
            String reply
    ) {
    }
}
