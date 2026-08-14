package com.ssafy.ieumgil.global.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 만료 세션 스윕 — 유휴 뷰어 차단의 마지막 조각 회귀 테스트.
 *
 * <p>인터셉터는 보내는 프레임만 검사하므로, 브로커 push만 받는 유휴 세션은 토큰 만료 후에도
 * 수신이 이어진다. 스윕이 만료 세션을 닫아야 차단이 완성된다.
 */
class ExpiredWsSessionSweeperTest {

    private WsSessionRegistry registry;
    private ExpiredWsSessionSweeper sweeper;

    @BeforeEach
    void setUp() {
        registry = new WsSessionRegistry();
        sweeper = new ExpiredWsSessionSweeper(registry);
    }

    private WebSocketSession bindSession(String sessionId, Map<String, Object> attributes) {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(sessionId);
        given(session.getAttributes()).willReturn(attributes);
        registry.bindTransport(session);
        return session;
    }

    private Map<String, Object> expiryAttr(Instant expiresAt) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(StompAuthInterceptor.TOKEN_EXPIRY_ATTR, expiresAt);
        return attributes;
    }

    @Test
    @DisplayName("만료 시각이 지난 세션은 닫는다 — 유휴 뷰어도 토큰 수명 뒤에는 수신이 끊긴다")
    void closesExpiredSession() throws IOException {
        WebSocketSession expired = bindSession("s1", expiryAttr(Instant.now().minusSeconds(10)));

        sweeper.closeExpiredSessions();

        verify(expired).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("만료 전 세션은 건드리지 않는다")
    void leavesUnexpiredSessionAlone() throws IOException {
        WebSocketSession alive = bindSession("s1", expiryAttr(Instant.now().plusSeconds(600)));

        sweeper.closeExpiredSessions();

        verify(alive, never()).close(any());
    }

    @Test
    @DisplayName("만료 시각이 아직 없는 세션(CONNECT 전)은 건너뛴다 — 구독이 없어 수신할 것도 없다")
    void skipsSessionWithoutExpiry() throws IOException {
        WebSocketSession handshaking = bindSession("s1", new HashMap<>());

        sweeper.closeExpiredSessions();

        verify(handshaking, never()).close(any());
    }

    @Test
    @DisplayName("한 세션의 close 실패가 나머지 세션 스윕을 막지 않는다")
    void continuesAfterCloseFailure() throws IOException {
        WebSocketSession failing = bindSession("s1", expiryAttr(Instant.now().minusSeconds(10)));
        WebSocketSession expired = bindSession("s2", expiryAttr(Instant.now().minusSeconds(10)));
        willThrow(new IOException("이미 닫히는 중")).given(failing).close(any());

        assertThatCode(() -> sweeper.closeExpiredSessions()).doesNotThrowAnyException();

        verify(expired).close(CloseStatus.POLICY_VIOLATION);
    }
}
