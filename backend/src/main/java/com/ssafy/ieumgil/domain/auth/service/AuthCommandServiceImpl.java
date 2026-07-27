package com.ssafy.ieumgil.domain.auth.service;

import com.ssafy.ieumgil.domain.auth.client.KakaoOAuthClient;
import com.ssafy.ieumgil.domain.auth.converter.AuthConverter;
import com.ssafy.ieumgil.domain.auth.dto.AuthResDTO;
import com.ssafy.ieumgil.domain.auth.dto.KakaoTokenResponse;
import com.ssafy.ieumgil.domain.auth.dto.KakaoUserInfoResponse;
import com.ssafy.ieumgil.domain.auth.exception.AuthErrorCode;
import com.ssafy.ieumgil.domain.auth.repository.RefreshTokenRepository;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.global.security.jwt.JwtProperties;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthCommandServiceImpl implements AuthCommandService {

    private static final String DEFAULT_NICKNAME = "이음길 사용자";

    private final KakaoOAuthClient kakaoOAuthClient;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;

    @Override
    public AuthResDTO.LoginResult kakaoLogin(String code) {
        // 1. 인가코드 → 카카오 액세스 토큰 → 사용자 정보
        KakaoTokenResponse kakaoToken = kakaoOAuthClient.requestToken(code);
        KakaoUserInfoResponse userInfo = kakaoOAuthClient.requestUserInfo(kakaoToken.accessToken());

        // 2. 기존 회원 조회, 없으면 가입
        User user = userRepository.findByKakaoId(userInfo.id()).orElse(null);
        boolean isNewUser = (user == null);
        if (isNewUser) {
            String nickname = userInfo.nickname() != null ? userInfo.nickname() : DEFAULT_NICKNAME;
            user = userRepository.save(User.builder()
                    .kakaoId(userInfo.id())
                    .nickname(nickname)
                    .profileImageUrl(userInfo.profileImageUrl())
                    .build());
        }

        // 3. 자체 JWT 발급 + refresh 토큰 Redis 저장
        String accessToken = jwtProvider.createAccessToken(user.getId());
        String refreshToken = jwtProvider.createRefreshToken(user.getId());
        refreshTokenRepository.save(user.getId(), refreshToken, jwtProperties.refreshTokenValidity());

        return AuthConverter.toLoginResult(user, accessToken, refreshToken, isNewUser);
    }

    @Override
    public AuthResDTO.ReissueResult reissue(String refreshToken) {
        Long userId = jwtProvider.getUserIdFromRefreshToken(refreshToken);

        String savedToken = refreshTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN));

        // 저장된 토큰과 불일치 → 탈취 가능성 → 세션 무효화 후 재로그인 유도
        if (!savedToken.equals(refreshToken)) {
            refreshTokenRepository.deleteByUserId(userId);
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        // RTR(Refresh Token Rotation): 재발급 시 refresh 토큰도 교체
        String newAccessToken = jwtProvider.createAccessToken(userId);
        String newRefreshToken = jwtProvider.createRefreshToken(userId);
        refreshTokenRepository.save(userId, newRefreshToken, jwtProperties.refreshTokenValidity());

        return AuthConverter.toReissueResult(newAccessToken, newRefreshToken);
    }

    @Override
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }
}
