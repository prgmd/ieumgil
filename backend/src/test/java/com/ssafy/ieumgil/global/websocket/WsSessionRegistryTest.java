package com.ssafy.ieumgil.global.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 탈퇴 시 WS 세션 강제 종료 (GRP-09).
 *
 * <p>인가 캐시만 비우는 방식으로는 부족하다 — 이미 성립한 구독에 브로커가 직접 밀어주는
 * 경로는 인바운드 인터셉터를 타지 않기 때문이다. 소켓 자체를 닫아야 실제로 끊긴다.
 */
class WsSessionRegistryTest {

    private static final Long MEMBER_ID = 10L;

    private WsSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WsSessionRegistry();
    }

    private WebSocketSession openSession(String sessionId, Long memberId) {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn(sessionId);
        registry.bindTransport(session);
        registry.register(memberId, sessionId);
        return session;
    }

    @Test
    @DisplayName("탈퇴한 멤버의 세션을 닫는다 — 열린 WS로 실시간 수신이 이어지지 않게")
    void disconnectClosesMemberSession() throws IOException {
        WebSocketSession session = openSession("s1", MEMBER_ID);

        registry.disconnect(MEMBER_ID);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("탭을 여러 개 열었으면 전부 닫는다 — 하나라도 남으면 차단이 아니다")
    void disconnectClosesEverySessionOfMember() throws IOException {
        WebSocketSession first = openSession("s1", MEMBER_ID);
        WebSocketSession second = openSession("s2", MEMBER_ID);

        registry.disconnect(MEMBER_ID);

        verify(first).close(CloseStatus.POLICY_VIOLATION);
        verify(second).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("다른 멤버의 세션은 건드리지 않는다")
    void disconnectLeavesOtherMembersAlone() throws IOException {
        WebSocketSession mine = openSession("s1", MEMBER_ID);
        WebSocketSession others = openSession("s2", 99L);

        registry.disconnect(MEMBER_ID);

        verify(mine).close(any(CloseStatus.class));
        verify(others, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("접속 중이 아닌 멤버의 탈퇴는 조용히 통과한다 — 탈퇴 트랜잭션을 깨지 않는다")
    void disconnectOnOfflineMemberIsNoop() {
        assertThatCode(() -> registry.disconnect(MEMBER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한 세션이 닫히다 실패해도 나머지는 계속 닫는다")
    void disconnectContinuesAfterCloseFailure() throws IOException {
        WebSocketSession failing = openSession("s1", MEMBER_ID);
        WebSocketSession healthy = openSession("s2", MEMBER_ID);
        willThrow(new IOException("already closing")).given(failing).close(any(CloseStatus.class));

        assertThatCode(() -> registry.disconnect(MEMBER_ID)).doesNotThrowAnyException();

        verify(healthy).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    @DisplayName("강제 종료가 매핑을 미리 지우지 않는다 — 이탈 처리(presence off)가 주인을 찾아야 한다")
    void disconnectKeepsMappingForDisconnectEvent() {
        openSession("s1", MEMBER_ID);

        registry.disconnect(MEMBER_ID);

        assertThat(registry.memberOf("s1")).isEqualTo(MEMBER_ID);
        assertThat(registry.unregister("s1")).isTrue();
        assertThat(registry.isOnline(MEMBER_ID)).isFalse();
    }

    @Test
    @DisplayName("세션이 끊기면 전송 세션도 함께 버린다 — 닫힌 소켓을 붙들면 누수다")
    void unregisterDropsTransport() throws IOException {
        WebSocketSession session = openSession("s1", MEMBER_ID);

        registry.unregister("s1");
        registry.register(MEMBER_ID, "s1");   // 매핑만 되살려도 전송 세션은 없어야 한다
        registry.disconnect(MEMBER_ID);

        verify(session, never()).close(any(CloseStatus.class));
    }
}
