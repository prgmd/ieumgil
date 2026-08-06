package com.ssafy.ieumgil.global.security;

import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 계정이 아직 살아 있는지 판정 — 인증의 단일 출처.
 *
 * <p>JWT는 서명·만료만 증명할 뿐 <b>계정이 아직 존재하는지는 증명하지 못한다</b>.
 * 탈퇴는 refresh 토큰을 지우지만 이미 발급된 access 토큰은 최대 30분 더 유효하므로,
 * 토큰만 믿으면 그 30분 동안 탈퇴한 계정이 그대로 통과한다(그룹 재가입 등).
 *
 * <p>REST(JwtAuthenticationFilter)와 STOMP CONNECT(StompAuthInterceptor)가 같은 판정을
 * 써야 해서 별도 컴포넌트로 둔다 — 각자 들고 있으면 한쪽만 고쳐지고 그쪽이 우회로가 된다
 * (ProjectMembership을 WS 인가의 단일 출처로 둔 것과 같은 이유).
 */
@Component
@RequiredArgsConstructor
public class ActiveUserChecker {

    private final UserRepository userRepository;

    /** @return 탈퇴하지 않은 회원이면 true. 존재하지 않는 회원도 false다 — 인증상 둘은 같다 */
    public boolean isActive(Long userId) {
        return userId != null && userRepository.existsByIdAndDeletedAtIsNull(userId);
    }
}
