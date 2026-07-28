package com.ssafy.ieumgil.domain.chatbot.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChatbotReqDTO {

    @Schema(description = "챗봇 메시지 전송 요청")
    public record SendMessage(
            @Schema(description = "사용자 메시지", example = "제주도 2박3일 여행지 하나만 추천해줘")
            @NotBlank(message = "메시지는 필수입니다.") @Size(max = 2000, message = "메시지는 2000자를 넘을 수 없습니다.")
            String message
    ) {
    }
}
