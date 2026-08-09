package com.ssafy.ieumgil.global.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * presence(PRS-01) — 접속/이탈을 /topic/project/{id}/presence 로 알린다.
 *
 * "보드를 보는 중"의 기준은 메인 op 토픽 구독이다. 구독은 인가(StompAuthInterceptor)를
 * 이미 통과한 뒤에만 이벤트로 도착하므로 여기서는 멤버십을 다시 검사하지 않는다.
 *
 * presence 메시지는 seq가 없고 저널에도 남지 않는다 — 재접속하면 스냅샷의
 * members[].online이 최신 상태를 주므로 재전송이 필요 없는 정보다.
 */
@Component
@RequiredArgsConstructor
public class PresenceEventListener {

    private static final Pattern MAIN_TOPIC = Pattern.compile("^/topic/project/(\\d+)$");

    private final PresenceRegistry presenceRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    /** sessionId → 그 세션이 보고 있는 projectId들 (disconnect 정리용) */
    private final Map<String, Set<Long>> projectsBySession = new ConcurrentHashMap<>();

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        if (destination == null || sessionId == null
                || !(event.getUser() instanceof StompPrincipal principal)) {
            return;
        }
        Matcher matcher = MAIN_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return;   // presence·cursor 등 서브 토픽 구독은 "보는 중" 판정에 안 센다
        }
        Long projectId = Long.parseLong(matcher.group(1));

        projectsBySession.computeIfAbsent(sessionId, id -> ConcurrentHashMap.newKeySet()).add(projectId);
        if (presenceRegistry.enter(projectId, principal.userId(), sessionId)) {
            broadcast(projectId, principal.userId(), true);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        Set<Long> projects = projectsBySession.remove(sessionId);
        if (projects == null || !(event.getUser() instanceof StompPrincipal principal)) {
            return;
        }
        for (Long projectId : projects) {
            if (presenceRegistry.leave(projectId, principal.userId(), sessionId)) {
                broadcast(projectId, principal.userId(), false);
            }
        }
    }

    private void broadcast(Long projectId, Long memberId, boolean online) {
        messagingTemplate.convertAndSend("/topic/project/" + projectId + "/presence",
                Map.of("type", "PRESENCE", "memberId", memberId, "online", online));
    }
}
