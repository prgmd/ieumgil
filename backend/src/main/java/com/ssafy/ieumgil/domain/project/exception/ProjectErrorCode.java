package com.ssafy.ieumgil.domain.project.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ProjectErrorCode implements BaseErrorCode {

    // 400
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "PROJECT400", "종료일은 시작일보다 빠를 수 없습니다."),

    // 404
    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "PROJECT404", "존재하지 않는 프로젝트입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
