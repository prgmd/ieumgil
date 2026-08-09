package com.ssafy.ieumgil.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.ssafy.ieumgil.global.security.jwt.JwtProvider;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * STOMP 프레임 인증·인가 (필수 보안 요건).
 *
 * REST는 JwtAuthenticationFilter가 막지만 **STOMP 프레임은 서블릿 필터를 타지 않는다** —
 * 여기서 막지 않으면 토큰 없이 아무 프로젝트나 구독할 수 있다.
 *
 * - CONNECT: Authorization 헤더의 JWT 검증(기존 JwtProvider 재사용) → Principal 바인딩
 * - SUBSCRIBE / SEND: 토큰 만료 확인 → destination에서 projectId 파싱 → 그룹 멤버십 검증
 *   통과한 projectId는 세션 어트리뷰트에 캐시한다 — cursor처럼 초당 수십 건 오는
 *   프레임마다 DB를 칠 수는 없다. 탈퇴 시 세션 강제 종료는 WsSessionRegistry가 맡는다.
 *
 * <p><b>두 커맨드는 허용 목적지가 다르다</b> — 같은 규칙으로 묶으면 안 된다.
 * SUBSCRIBE는 /topic/project/{id}/**(+ 개인 큐 /user/**)를, SEND는 /app/project/{id}/** 만
 * 허용한다. /topic·/user로의 SEND가 왜 방화벽 구멍인지는 authorizeSend의 주석에 적어 두었다.
 *
 * <p><b>토큰 만료 (GRP-09)</b>: CONNECT 이후 프레임에는 토큰이 실리지 않으므로, CONNECT 때
 * 만료 시각을 세션에 남겨 매 프레임 다시 본다. 이것이 없으면 유효 토큰으로 한 번 연결한 세션은
 * 만료·로그아웃과 무관하게 영원히 살아 있는 채널이 된다.
 * 남는 틈: 이미 성립한 구독은 브로커가 직접 밀어주므로 이 인터셉터를 타지 않는다. 즉
 * <i>완전히 유휴한</i> 뷰어는 만료 후에도 수신이 이어진다. 프레임을 하나라도 보내는 순간
 * 거부되고 연결이 끊기므로 활성 사용자는 즉시 차단된다.
 *
 * 검증 실패는 예외로 던진다 — Spring이 ERROR 프레임을 보내고 연결을 끊는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    /** 구독 가능한 브로드캐스트 토픽 — /topic/project/{id}(/presence|/cursor) */
    private static final Pattern SUBSCRIBE_DESTINATION =
            Pattern.compile("^/topic/project/(\\d+)(?:/.*)?$");

    /** 전송 가능한 애플리케이션 목적지 — /app/project/{id}/... (반드시 @MessageMapping을 거친다) */
    private static final Pattern SEND_DESTINATION =
            Pattern.compile("^/app/project/(\\d+)(?:/.*)?$");

    private static final String AUTHORIZED_PROJECTS_ATTR = "authorizedProjectIds";

    /** CONNECT 때 저장하는 access 토큰 만료 시각 (Instant) — 이후 프레임의 만료 판정 기준 */
    static final String TOKEN_EXPIRY_ATTR = "accessTokenExpiry";

    private final JwtProvider jwtProvider;
    private final ProjectMembership projectMembership;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        switch (accessor.getCommand()) {
            case CONNECT -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscribe(accessor);
            case SEND -> authorizeSend(accessor);
            default -> { /* ACK, DISCONNECT 등은 통과 */ }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String bearer = accessor.getFirstNativeHeader("Authorization");
        if (bearer == null || !bearer.startsWith("Bearer ")) {
            throw new IllegalArgumentException("WS CONNECT에 Bearer 토큰이 없습니다.");
        }
        // 서명·만료·type 검증 전부 기존 JwtProvider가 한다. 실패 시 CustomException → 연결 거부
        String token = bearer.substring("Bearer ".length());
        Long userId = jwtProvider.getUserIdFromAccessToken(token);
        accessor.setUser(new StompPrincipal(userId));

        // 세션 어트리뷰트 초기화 — 이후 프레임의 인가 캐시로 쓴다
        Map<String, Object> attributes = sessionAttributes(accessor);
        attributes.put(AUTHORIZED_PROJECTS_ATTR, ConcurrentHashMap.newKeySet());
        attributes.put(TOKEN_EXPIRY_ATTR, jwtProvider.getAccessTokenExpiry(token));
    }

    /**
     * 구독 인가 — 프로젝트 토픽(멤버십 확인)과 본인 개인 큐만 허용한다.
     */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        requireUnexpiredToken(accessor);
        String destination = requireDestination(accessor);

        // 개인 큐(/user/queue/...) 구독은 Spring이 Principal 기준으로 라우팅하므로
        // 남의 것을 구독할 방법 자체가 없다 — 인증만 확인하고 통과시킨다.
        // 이 근거는 SUBSCRIBE에만 성립한다(authorizeSend 참고).
        if (destination.startsWith("/user/")) {
            requirePrincipal(accessor);
            return;
        }

        authorizeProject(accessor, destination, SUBSCRIBE_DESTINATION);
    }

    /**
     * 전송 인가 — /app/project/{id}/** 만 허용한다. /topic·/user는 <b>수신 전용</b>이다.
     *
     * <p>구독과 같은 규칙으로 통과시키면 안 된다:
     * <ul>
     *   <li><b>/topic</b>: SimpleBroker가 직접 담당하는 prefix라, 클라이언트 SEND는
     *       {@code @MessageMapping}을 타지 않고 브로커가 원문 그대로 전 구독자에게 뿌린다.
     *       멤버 한 명이 서버가 발행한 것처럼 op(seq·actorId 포함)를 위조할 수 있고,
     *       DB는 무변경이라 서버 로그에도 흔적이 남지 않는다.</li>
     *   <li><b>/user</b>: DefaultUserDestinationResolver가 <i>목적지에 적힌</i> 사용자명으로
     *       해석해 그 사용자의 큐로 배달한다. 남의 userId를 적으면 그대로 도달하므로
     *       RealtimeRelayController의 대상 멤버십 검증이 통째로 우회된다.</li>
     * </ul>
     * 프론트가 실제로 SEND하는 곳은 cursor와 voice/signal 둘뿐이고 모두 /app/** 이다.
     */
    private void authorizeSend(StompHeaderAccessor accessor) {
        requireUnexpiredToken(accessor);
        authorizeProject(accessor, requireDestination(accessor), SEND_DESTINATION);
    }

    /** 목적지에서 projectId를 뽑아 멤버십을 확인한다. 통과한 projectId는 세션에 캐시한다 */
    private void authorizeProject(StompHeaderAccessor accessor, String destination, Pattern allowed) {
        Matcher matcher = allowed.matcher(destination);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("허용되지 않은 destination: " + destination);
        }
        Long projectId = Long.parseLong(matcher.group(1));
        Long userId = requirePrincipal(accessor).userId();

        @SuppressWarnings("unchecked")
        Set<Long> authorized = (Set<Long>) sessionAttributes(accessor).get(AUTHORIZED_PROJECTS_ATTR);
        if (authorized != null && authorized.contains(projectId)) {
            return;   // 캐시 히트 — DB 조회 없음
        }

        if (!projectMembership.isMember(projectId, userId)) {
            log.warn("WS 인가 거부: user={} project={} (비멤버)", userId, projectId);
            throw new IllegalArgumentException("그룹 멤버가 아닙니다.");
        }
        if (authorized != null) {
            authorized.add(projectId);
        }
    }

    private String requireDestination(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new IllegalArgumentException("destination이 없습니다.");
        }
        return destination;
    }

    /**
     * CONNECT 때 남겨 둔 만료 시각을 확인한다.
     *
     * <p>만료 시각이 없는 세션은 CONNECT를 정상적으로 거치지 않은 것이므로 거부한다 —
     * "없으면 통과"로 두면 어트리뷰트를 못 심은 경로가 곧 우회로가 된다.
     */
    private void requireUnexpiredToken(StompHeaderAccessor accessor) {
        Object expiry = sessionAttributes(accessor).get(TOKEN_EXPIRY_ATTR);
        if (!(expiry instanceof Instant expiresAt)) {
            throw new IllegalArgumentException("인증되지 않은 세션입니다.");
        }
        if (Instant.now().isAfter(expiresAt)) {
            log.warn("WS 인가 거부: session={} (토큰 만료)", accessor.getSessionId());
            throw new IllegalArgumentException("토큰이 만료되었습니다. 다시 연결해주세요.");
        }
    }

    private StompPrincipal requirePrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompPrincipal principal) {
            return principal;
        }
        throw new IllegalArgumentException("인증되지 않은 세션입니다.");
    }

    private Map<String, Object> sessionAttributes(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            throw new IllegalStateException("세션 어트리뷰트가 없습니다.");
        }
        return attributes;
    }
}
