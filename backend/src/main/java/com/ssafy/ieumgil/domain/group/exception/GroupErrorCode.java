package com.ssafy.ieumgil.domain.group.exception;

import com.ssafy.ieumgil.global.apiPayload.code.BaseErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum GroupErrorCode implements BaseErrorCode {

    // 400
    GROUP_NAME_MISMATCH(HttpStatus.BAD_REQUEST, "GROUP400", "그룹명이 일치하지 않습니다."),

    // 403
    NOT_GROUP_MEMBER(HttpStatus.FORBIDDEN, "GROUP403", "해당 그룹의 멤버가 아닙니다."),

    // 404
    GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "GROUP404", "존재하지 않는 그룹입니다."),
    INVITE_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "GROUP404_1", "존재하지 않는 초대 코드입니다."),

    // 409 — 요청은 올바르지만 지금 상태와 맞지 않음
    ALREADY_GROUP_MEMBER(HttpStatus.CONFLICT, "GROUP409", "이미 소속된 그룹입니다."),
    GROUP_FULL(HttpStatus.CONFLICT, "GROUP409_1", "그룹 정원(10명)이 가득 찼습니다."),

    // 410 — 예전엔 있었지만 지금은 없어짐
    INVITE_CODE_EXPIRED(HttpStatus.GONE, "GROUP410", "만료된 초대 코드입니다. 멤버에게 재발급을 요청해주세요."),

    // 500
    INVITE_CODE_ISSUE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "GROUP500", "초대 코드 발급에 실패했습니다. 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
