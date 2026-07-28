package com.ssafy.ieumgil.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoUserInfoResponse(
        Long id,
        @JsonProperty("kakao_account") KakaoAccount kakaoAccount
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KakaoAccount(
            Profile profile
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Profile(
            String nickname,
            @JsonProperty("profile_image_url") String profileImageUrl
    ) {
    }

    // 동의 항목 미설정 시 null 이 내려올 수 있어 안전하게 꺼낸다
    public String nickname() {
        if (kakaoAccount == null || kakaoAccount.profile() == null) {
            return null;
        }
        return kakaoAccount.profile().nickname();
    }

    public String profileImageUrl() {
        if (kakaoAccount == null || kakaoAccount.profile() == null) {
            return null;
        }
        return kakaoAccount.profile().profileImageUrl();
    }
}
