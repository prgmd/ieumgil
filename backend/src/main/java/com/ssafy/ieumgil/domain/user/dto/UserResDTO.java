package com.ssafy.ieumgil.domain.user.dto;

import lombok.Builder;

public class UserResDTO {

    /**
     * 내 정보 조회 응답 (GET /api/members/me).
     * 엔티티의 kakaoId·createdAt 등은 노출하지 않고 화면에 필요한 값만 담는다.
     */
    @Builder
    public record Me(
            Long id,
            String nickname,
            String profileImg
    ) {
    }
}
