package com.ssafy.ieumgil.domain.auth.converter;

import com.ssafy.ieumgil.domain.auth.dto.AuthResDTO;
import com.ssafy.ieumgil.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthConverter {

    public static AuthResDTO.LoginResult toLoginResult(User user, String accessToken, String refreshToken, boolean isNewUser) {
        AuthResDTO.Login login = AuthResDTO.Login.builder()
                .userId(user.getId())
                .nickname(user.getNickname())
                .profileImageUrl(user.getProfileImageUrl())
                .accessToken(accessToken)
                .isNewUser(isNewUser)
                .build();
        return new AuthResDTO.LoginResult(login, refreshToken);
    }

    public static AuthResDTO.ReissueResult toReissueResult(String accessToken, String refreshToken) {
        AuthResDTO.Reissue reissue = AuthResDTO.Reissue.builder()
                .accessToken(accessToken)
                .build();
        return new AuthResDTO.ReissueResult(reissue, refreshToken);
    }
}
