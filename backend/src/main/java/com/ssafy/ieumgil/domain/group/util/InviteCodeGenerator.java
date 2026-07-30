package com.ssafy.ieumgil.domain.group.util;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * 그룹 초대 코드 생성기 (ERD: CHAR(8), 대문자+숫자, I·O·0·1 제외).
 * 사람이 눈으로 읽고 손으로 입력하는 코드라 혼동되는 글자를 뺀다.
 */
@Component
public class InviteCodeGenerator {

    /** I·O·0·1을 제외한 32글자. 대문자 24개 + 숫자 8개 */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final int CODE_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);

        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }

        return code.toString();
    }
}
