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

    // 500
    INVITE_CODE_ISSUE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "GROUP500", "초대 코드 발급에 실패했습니다. 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
