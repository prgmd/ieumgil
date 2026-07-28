package com.ssafy.ieumgil.domain.auth.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {

    // 401
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_1", "유효하지 않은 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_2", "만료된 토큰입니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH401_3", "유효하지 않은 리프레시 토큰입니다. 다시 로그인해주세요."),

    // 502
    KAKAO_TOKEN_FAILED(HttpStatus.BAD_GATEWAY, "AUTH502_1", "카카오 토큰 발급에 실패했습니다."),
    KAKAO_USER_INFO_FAILED(HttpStatus.BAD_GATEWAY, "AUTH502_2", "카카오 사용자 정보 조회에 실패했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
