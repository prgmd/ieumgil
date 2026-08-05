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
import com.ssafy.ieumgil.global.websocket.WsSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 의도적으로 클래스 레벨 {@code @Transactional}이 없다.
 *
 * kakaoLogin은 카카오 외부 HTTP를 2회 호출하는데, 트랜잭션 안에서 부르면 그 왕복 내내
 * DB 커넥션을 물고 있게 된다 — 카카오가 지연되면 커넥션 풀이 로그인 요청들로 고갈되어
 * 서비스 전체가 멈춘다. 남은 DB 작업은 조회 1회 + 신규 가입 시 save 1회뿐이라 리포지토리
 * 자체 트랜잭션으로 충분하고, 둘을 한 트랜잭션으로 묶어도 동시 가입 경합은 어차피 못 막는다
 * (kakao_id UNIQUE가 최후 방어선). reissue/logout은 Redis만 만져 JPA 트랜잭션이 무의미하다.
 * 이 클래스에 여러 DB 쓰기가 생기면 그때는 DB 작업만 별도 빈으로 묶어 트랜잭션을 걸 것 —
 * 외부 호출을 다시 트랜잭션 안으로 들이지 말 것.
 */
@Service
@RequiredArgsConstructor
public class AuthCommandServiceImpl implements AuthCommandService {

    private static final String DEFAULT_NICKNAME = "이음길 사용자";

    private final KakaoOAuthClient kakaoOAuthClient;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final WsSessionRegistry wsSessionRegistry;

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

    /**
     * 로그아웃 — refresh 토큰 삭제 + 열려 있는 WS 세션 종료.
     *
     * <p>세션을 끊지 않으면 로그아웃이 REST에만 적용된다. 이미 성립한 구독은 브로커가 직접
     * 밀어주므로 StompAuthInterceptor를 타지 않고, 프레임을 하나도 보내지 않는 세션은
     * access 토큰이 만료돼도 수신이 이어진다 — 토큰을 탈취당한 뒤 로그아웃해도 차단되지 않는다.
     * (탈퇴·그룹 탈퇴 경로는 이미 같은 호출을 한다)
     */
    @Override
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
        wsSessionRegistry.disconnect(userId);
    }
}
