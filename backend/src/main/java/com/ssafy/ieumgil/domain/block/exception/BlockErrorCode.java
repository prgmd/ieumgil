package com.ssafy.ieumgil.domain.block.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum BlockErrorCode implements BaseErrorCode {

    // 400
    COORDINATE_REQUIRED(HttpStatus.BAD_REQUEST, "BLOCK400", "장소성 블록(SPOT/FOOD/STAY)은 좌표(lat/lng)가 필요합니다."),
    VEHICLE_FLAG_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "BLOCK400_1", "vehicleFlag는 ETC 카테고리에서만 지정할 수 있습니다."),
    UNSUPPORTED_FIELD(HttpStatus.BAD_REQUEST, "BLOCK400_2", "LWW 갱신을 지원하지 않는 필드입니다."),
    INVALID_FIELD_VALUE(HttpStatus.BAD_REQUEST, "BLOCK400_3", "필드 값의 형식이 올바르지 않습니다."),

    // 404
    BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "BLOCK404", "존재하지 않는 블록입니다."),

    // 409 — 하트비트/연장은 락 소유자만 가능
    LOCK_NOT_HELD(HttpStatus.CONFLICT, "BLOCK409", "이 블록의 편집 락을 보유하고 있지 않습니다."),

    // 409 — 남이 detail 편집 락을 쥔 동안의 detail 쓰기 거부 (BLK-08 서버 강제)
    DETAIL_LOCKED_BY_OTHER(HttpStatus.CONFLICT, "BLOCK409_1", "다른 멤버가 세부 내용을 편집 중입니다."),

    /**
     * 410 — tombstone 처리된 블록에 도착한 지연 op (BLK-09).
     * 404("없었다")와 구분해야 프론트가 해당 블록만 조용히 화면에서 제거할 수 있다.
     */
    BLOCK_GONE(HttpStatus.GONE, "BLOCK410", "이미 삭제된 블록입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
