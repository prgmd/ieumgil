package com.ssafy.ieumgil.global.security.jwt;

import com.ssafy.ieumgil.domain.auth.exception.AuthErrorCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.global.security.ActiveUserChecker;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 인증 필터의 <b>계정 생존 확인</b>(S1) 회귀 테스트.
 *
 * <p>JWT는 서명·만료만 증명한다. 탈퇴는 refresh 토큰만 지우므로, 이 검사가 없으면
 * 이미 발급된 access 토큰으로 최대 30분간 탈퇴한 계정이 그대로 통과한다
 * (그룹 재가입으로 정원을 점유하고, 그동안의 편집이 "탈퇴한 사용자" 명의로 남는다).
 *
 * <p>거부는 예외가 아니라 <i>컨텍스트를 비운 채 체인 진행</i>으로 표현한다 —
 * 401 응답은 JwtAuthenticationEntryPoint의 몫이고, permitAll 경로는 그대로 통과해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final Long USER_ID = 7L;
    private static final String TOKEN = "valid.jwt.token";

    @Mock
    private JwtProvider jwtProvider;
    @Mock
    private ActiveUserChecker activeUserChecker;
    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    @Test
    @DisplayName("활성 회원의 토큰이면 인증이 설정된다 — 정상 흐름은 그대로다")
    void authenticatesActiveUser() throws Exception {
        given(jwtProvider.getUserIdFromAccessToken(TOKEN)).willReturn(USER_ID);
        given(activeUserChecker.isActive(USER_ID)).willReturn(true);

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(USER_ID);
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("탈퇴한 회원의 토큰은 인증하지 않는다 — 서명·만료가 유효해도 거부")
    void rejectsWithdrawnUser() throws Exception {
        given(jwtProvider.getUserIdFromAccessToken(TOKEN)).willReturn(USER_ID);
        given(activeUserChecker.isActive(USER_ID)).willReturn(false);

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), filterChain);

        // 인증이 없으니 보호된 경로는 EntryPoint가 401을 낸다
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        // 예외로 끊지 않는다 — permitAll 경로(로그인·재발급)는 계속 통과해야 한다
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("유효하지 않은 토큰이면 계정 조회까지 가지 않는다 — 불필요한 DB 조회 방지")
    void skipsLookupForInvalidToken() throws Exception {
        willThrow(new CustomException(AuthErrorCode.INVALID_TOKEN))
                .given(jwtProvider).getUserIdFromAccessToken(TOKEN);

        filter.doFilter(requestWithToken(), new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(activeUserChecker);
    }

    @Test
    @DisplayName("토큰이 없으면 아무것도 하지 않는다 — 익명 요청 경로 유지")
    void ignoresRequestWithoutToken() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtProvider, activeUserChecker);
    }
}
