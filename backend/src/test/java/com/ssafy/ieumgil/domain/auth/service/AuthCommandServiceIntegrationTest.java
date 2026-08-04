package com.ssafy.ieumgil.domain.auth.service;

import com.ssafy.ieumgil.domain.auth.client.KakaoOAuthClient;
import com.ssafy.ieumgil.domain.auth.dto.AuthResDTO;
import com.ssafy.ieumgil.domain.auth.dto.KakaoTokenResponse;
import com.ssafy.ieumgil.domain.auth.dto.KakaoUserInfoResponse;
import com.ssafy.ieumgil.domain.auth.repository.RefreshTokenRepository;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * kakaoLogin의 트랜잭션 경계 회귀 테스트.
 *
 * 전에는 클래스 레벨 @Transactional 안에서 카카오 HTTP를 2회 호출했다 — 카카오가
 * 응답을 물고 있으면 그 왕복 내내 DB 커넥션을 점유해, 로그인 몇 건만 밀려도 커넥션 풀이
 * 고갈되어 서비스 전체가 멈추는 경로였다. 외부 호출은 반드시 트랜잭션 밖이어야 한다 —
 * 누가 다시 @Transactional을 클래스에 붙이면 이 테스트가 깨진다.
 */
class AuthCommandServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    AuthCommandService authCommandService;
    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    KakaoOAuthClient kakaoOAuthClient;

    private static KakaoUserInfoResponse userInfo(long kakaoId) {
        return new KakaoUserInfoResponse(kakaoId,
                new KakaoUserInfoResponse.KakaoAccount(
                        new KakaoUserInfoResponse.Profile("카카오테스터", "http://img.example/p.png")));
    }

    @Test
    @DisplayName("카카오 HTTP 호출은 DB 트랜잭션 밖에서 실행된다")
    void kakaoCallsRunOutsideDbTransaction() {
        long kakaoId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        AtomicBoolean tokenCallInTx = new AtomicBoolean(true);
        AtomicBoolean userInfoCallInTx = new AtomicBoolean(true);

        when(kakaoOAuthClient.requestToken("auth-code")).thenAnswer(inv -> {
            tokenCallInTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new KakaoTokenResponse("bearer", "kakao-access", 3600, "kakao-refresh", 7200);
        });
        when(kakaoOAuthClient.requestUserInfo(anyString())).thenAnswer(inv -> {
            userInfoCallInTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return userInfo(kakaoId);
        });

        authCommandService.kakaoLogin("auth-code");

        assertThat(tokenCallInTx).as("requestToken이 DB 트랜잭션 안에서 불렸다").isFalse();
        assertThat(userInfoCallInTx).as("requestUserInfo가 DB 트랜잭션 안에서 불렸다").isFalse();
    }

    @Test
    @DisplayName("최초 로그인은 가입 처리되고, 재로그인은 기존 회원으로 이어진다 — refresh 토큰도 저장된다")
    void loginCreatesUserThenRecognizesReturningUser() {
        long kakaoId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        when(kakaoOAuthClient.requestToken("auth-code"))
                .thenReturn(new KakaoTokenResponse("bearer", "kakao-access", 3600, "kakao-refresh", 7200));
        when(kakaoOAuthClient.requestUserInfo(anyString())).thenReturn(userInfo(kakaoId));

        AuthResDTO.LoginResult first = authCommandService.kakaoLogin("auth-code");
        assertThat(first.login().isNewUser()).isTrue();
        assertThat(first.login().accessToken()).isNotBlank();
        assertThat(refreshTokenRepository.findByUserId(first.login().userId()))
                .contains(first.refreshToken());

        User saved = userRepository.findByKakaoId(kakaoId).orElseThrow();
        assertThat(saved.getNickname()).isEqualTo("카카오테스터");

        AuthResDTO.LoginResult second = authCommandService.kakaoLogin("auth-code");
        assertThat(second.login().isNewUser()).isFalse();
        assertThat(second.login().userId()).isEqualTo(first.login().userId());
    }
}
