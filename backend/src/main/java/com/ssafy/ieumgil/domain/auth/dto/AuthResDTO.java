package com.ssafy.ieumgil.domain.auth.dto;

import lombok.Builder;

public class AuthResDTO {

    @Builder
    public record Login(
            Long userId,
            String nickname,
            String profileImageUrl,
            String accessToken,
            boolean isNewUser
    ) {
    }

    @Builder
    public record Reissue(
            String accessToken
    ) {
    }

    // 서비스 → 컨트롤러 전달용. refreshToken은 응답 body가 아니라 httpOnly 쿠키(Set-Cookie)로 내려간다.
    public record LoginResult(
            Login login,
            String refreshToken
    ) {
    }

    public record ReissueResult(
            Reissue reissue,
            String refreshToken
    ) {
    }
}
