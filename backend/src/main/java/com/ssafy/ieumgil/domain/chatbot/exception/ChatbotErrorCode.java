package com.ssafy.ieumgil.domain.chatbot.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ChatbotErrorCode implements BaseErrorCode {

    GMS_CALL_FAILED(HttpStatus.BAD_GATEWAY, "CHATBOT502", "GMS 응답을 받지 못했습니다."),
    HISTORY_STORE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "CHATBOT500", "대화 히스토리 처리에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
