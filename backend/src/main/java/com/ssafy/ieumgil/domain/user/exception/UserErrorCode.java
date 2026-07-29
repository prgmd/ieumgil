package com.ssafy.ieumgil.domain.user.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements BaseErrorCode {

    // 404
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER404", "존재하지 않는 회원입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
