package com.ssafy.ieumgil.domain.transit.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum TransitErrorCode implements BaseErrorCode {

    ODSAY_API_CALL_FAILED(HttpStatus.BAD_GATEWAY, "TRANSIT502", "ODsay API 응답을 받지 못했습니다."),
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "TRANSIT404", "해당 구간의 대중교통 경로를 찾을 수 없습니다."),
    UNSUPPORTED_MODE(HttpStatus.BAD_REQUEST, "TRANSIT400", "현재 지원하지 않는 이동수단입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
