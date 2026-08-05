package com.ssafy.ieumgil.global.websocket;

import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * STOMP 프레임 인증·인가 — 특히 <b>토큰 만료</b>(GRP-09).
 *
 * <p>CONNECT 이후 프레임에는 토큰이 실리지 않는다. CONNECT 때 만료 시각을 세션에 남겨
 * 매 프레임 확인하지 않으면, 한 번 연결된 세션은 만료·로그아웃·탈퇴와 무관하게 영원히
 * 살아 있는 채널이 된다.
 */
@ExtendWith(MockitoExtension.class)
class StompAuthInterceptorTest {

    private static final Long USER_ID = 10L;
    private static final Long PROJECT_ID = 1L;
    private static final String TOKEN = "valid.jwt.token";

    @Mock
    private JwtProvider jwtProvider;
    @Mock
    private ProjectMembership projectMembership;
    @Mock
    private MessageChannel channel;

    @InjectMocks
    private StompAuthInterceptor interceptor;

    /** 세션 어트리뷰트를 공유하는 프레임 — 실제 세션처럼 CONNECT가 심은 값을 이후 프레임이 본다 */
    private Message<byte[]> frame(StompCommand command, Map<String, Object> sessionAttributes,
                                  String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(sessionAttributes);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (command != StompCommand.CONNECT) {
            accessor.setUser(new StompPrincipal(USER_ID));
        }
        // 인터셉터가 accessor를 다시 고친다(Principal 바인딩 등) — 기본값은 getMessageHeaders()
        // 시점에 immutable로 굳어 실제 런타임과 달라진다
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Map<String, Object> connectedSession(Instant expiry) {
        Map<String, Object> attributes = new HashMap<>();
        given(jwtProvider.getUserIdFromAccessToken(TOKEN)).willReturn(USER_ID);
        given(jwtProvider.getAccessTokenExpiry(TOKEN)).willReturn(expiry);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("session-1");
        accessor.setSessionAttributes(attributes);
        accessor.setNativeHeader("Authorization", "Bearer " + TOKEN);
        accessor.setLeaveMutable(true);
        interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()), channel);
        return attributes;
    }

    @Test
    @DisplayName("CONNECT가 토큰 만료 시각을 세션에 남긴다 — 이후 프레임의 판정 기준")
    void connectStoresTokenExpiry() {
        Instant expiry = Instant.now().plus(30, ChronoUnit.MINUTES);

        Map<String, Object> attributes = connectedSession(expiry);

        assertThat(attributes.get(StompAuthInterceptor.TOKEN_EXPIRY_ATTR)).isEqualTo(expiry);
    }

    @Test
    @DisplayName("토큰이 만료된 뒤의 SUBSCRIBE는 거부한다 — 멤버십은 보지도 않는다")
    void rejectsSubscribeAfterTokenExpiry() {
        Map<String, Object> session = connectedSession(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, session, "/topic/project/1"), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료");
        verifyNoInteractions(projectMembership);
    }

    @Test
    @DisplayName("토큰이 만료된 뒤의 SEND도 거부한다 — 커서/보이스 발신이 이어지면 안 된다")
    void rejectsSendAfterTokenExpiry() {
        Map<String, Object> session = connectedSession(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SEND, session, "/app/project/1/cursor"), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료");
    }

    @Test
    @DisplayName("개인 큐 구독도 만료를 확인한다 — /user/ 라고 무사통과시키지 않는다")
    void rejectsUserQueueSubscribeAfterTokenExpiry() {
        Map<String, Object> session = connectedSession(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, session, "/user/queue/voice"), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("만료");
    }

    @Test
    @DisplayName("만료 시각이 없는 세션은 거부한다 — CONNECT를 거치지 않은 우회로를 막는다")
    void rejectsSessionWithoutExpiryAttribute() {
        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, new HashMap<>(), "/topic/project/1"), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인증되지 않은 세션");
        verifyNoInteractions(projectMembership);
    }

    @Test
    @DisplayName("토큰이 유효하면 멤버십 검증까지 가고 통과한다")
    void allowsSubscribeWhileTokenValid() {
        Map<String, Object> session = connectedSession(Instant.now().plus(30, ChronoUnit.MINUTES));
        given(projectMembership.isMember(PROJECT_ID, USER_ID)).willReturn(true);

        assertThatCode(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, session, "/topic/project/1"), channel))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("유효 토큰이어도 비멤버면 거부한다 — 기존 인가가 그대로 살아 있다")
    void stillRejectsNonMemberWithValidToken() {
        Map<String, Object> session = connectedSession(Instant.now().plus(30, ChronoUnit.MINUTES));
        given(projectMembership.isMember(PROJECT_ID, USER_ID)).willReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, session, "/topic/project/1"), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("그룹 멤버가 아닙니다");
    }

    /**
     * SUBSCRIBE와 SEND는 허용 목적지가 다르다. 둘을 같은 규칙으로 묶으면 브로커가 담당하는
     * prefix(/topic, /user)로 클라이언트가 직접 쓸 수 있게 되고,
     * 그 프레임은 서버 핸들러(@MessageMapping)를 아예 거치지 않는다.
     */
    @Test
    @DisplayName("SEND /topic/** 은 멤버여도 거부한다 — 브로커 직행 = 서버를 사칭한 op 위조 경로")
    void rejectsSendToTopic() {
        Map<String, Object> session = connectedSession(Instant.now().plus(30, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SEND, session, "/topic/project/1"), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않은 destination");
        // 멤버십을 보기도 전에 막아야 한다 — "멤버면 통과"로 되돌리는 회귀를 여기서 잡는다
        verifyNoInteractions(projectMembership);
    }

    @Test
    @DisplayName("SEND /topic/**/cursor 도 거부한다 — 하위 경로로도 새어 나가지 않는다")
    void rejectsSendToTopicSubPath() {
        Map<String, Object> session = connectedSession(Instant.now().plus(30, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SEND, session, "/topic/project/1/cursor"), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않은 destination");
        verifyNoInteractions(projectMembership);
    }

    /**
     * 구독은 Principal 기준 라우팅이라 남의 것을 볼 수 없지만, 전송은 다르다 —
     * DefaultUserDestinationResolver가 목적지에 적힌 사용자명으로 해석해 그 큐로 배달하므로
     * RealtimeRelayController의 대상 멤버십 검증이 통째로 우회된다.
     */
    @Test
    @DisplayName("SEND /user/{남}/queue/voice 는 거부한다 — 릴레이의 멤버십 검증 우회 경로")
    void rejectsSendToOtherUserQueue() {
        Map<String, Object> session = connectedSession(Instant.now().plus(30, ChronoUnit.MINUTES));

        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SEND, session, "/user/9/queue/voice"), channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않은 destination");
        verifyNoInteractions(projectMembership);
    }

    @Test
    @DisplayName("정상 SEND(/app/project/{id}/**)는 멤버면 그대로 통과한다")
    void allowsSendToAppDestination() {
        Map<String, Object> session = connectedSession(Instant.now().plus(30, ChronoUnit.MINUTES));
        given(projectMembership.isMember(PROJECT_ID, USER_ID)).willReturn(true);

        assertThatCode(() -> interceptor.preSend(
                frame(StompCommand.SEND, session, "/app/project/1/voice/signal"), channel))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정상 SUBSCRIBE(개인 큐)는 그대로 통과한다 — voice 수신이 막히면 안 된다")
    void allowsUserQueueSubscribe() {
        Map<String, Object> session = connectedSession(Instant.now().plus(30, ChronoUnit.MINUTES));

        assertThatCode(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, session, "/user/queue/voice"), channel))
                .doesNotThrowAnyException();
        verifyNoInteractions(projectMembership);   // 개인 큐는 프로젝트 개념이 없다
    }

    @Test
    @DisplayName("정상 SUBSCRIBE(토픽 하위 경로)도 그대로 통과한다 — presence·cursor 구독")
    void allowsTopicSubPathSubscribe() {
        Map<String, Object> session = connectedSession(Instant.now().plus(30, ChronoUnit.MINUTES));
        given(projectMembership.isMember(PROJECT_ID, USER_ID)).willReturn(true);

        assertThatCode(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, session, "/topic/project/1/presence"), channel))
                .doesNotThrowAnyException();
    }
}
