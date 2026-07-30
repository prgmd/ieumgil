package com.ssafy.ieumgil.domain.place.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum PlaceErrorCode implements BaseErrorCode {

    KAKAO_API_CALL_FAILED(HttpStatus.BAD_GATEWAY, "PLACE502", "카카오 API 응답을 받지 못했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
