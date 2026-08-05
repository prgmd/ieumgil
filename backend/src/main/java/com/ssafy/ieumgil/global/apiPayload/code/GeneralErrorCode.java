package com.ssafy.ieumgil.global.apiPayload.code;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GeneralErrorCode implements BaseErrorCode {

    // 400
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON400", "잘못된 요청입니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "COMMON400_1", "요청 데이터 검증에 실패했습니다."),
    INVALID_TYPE(HttpStatus.BAD_REQUEST, "COMMON400_2", "요청 파라미터 타입이 올바르지 않습니다."),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "COMMON400_3", "필수 요청 파라미터가 누락되었습니다."),
    UNREADABLE_MESSAGE(HttpStatus.BAD_REQUEST, "COMMON400_4", "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해주세요."),

    // 401 / 403
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON401", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON403", "접근 권한이 없습니다."),

    // 404 / 405
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON404", "요청한 자원을 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON405", "지원하지 않는 HTTP 메서드입니다."),

    // 500
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON500", "서버 내부 오류가 발생했습니다."),

    // 503 — op 락 획득 시간 초과(OpPublisher). 데드락 안전망이 요청을 끊었다는 뜻이므로 재시도가 유효하다
    OP_LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "COMMON503", "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
