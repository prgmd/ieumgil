package com.ssafy.ieumgil.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * WS 세션 수명 이벤트 → 레지스트리 반영.
 * Step 5의 presence는 여기서 접속/이탈 브로드캐스트를 추가로 쏘게 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsSessionEventListener {

    private final WsSessionRegistry sessionRegistry;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (event.getUser() instanceof StompPrincipal principal && accessor.getSessionId() != null) {
            sessionRegistry.register(principal.userId(), accessor.getSessionId());
            log.debug("WS 접속: member={} session={}", principal.userId(), accessor.getSessionId());
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Long memberId = sessionRegistry.memberOf(sessionId);
        boolean lastSession = sessionRegistry.unregister(sessionId);
        if (memberId != null) {
            log.debug("WS 종료: member={} session={} last={}", memberId, sessionId, lastSession);
        }
    }
}
