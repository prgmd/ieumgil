package com.ssafy.ieumgil.domain.festival.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum FestivalErrorCode implements BaseErrorCode {

    TOUR_API_CALL_FAILED(HttpStatus.BAD_GATEWAY, "FESTIVAL502", "TourAPI 응답을 받지 못했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
