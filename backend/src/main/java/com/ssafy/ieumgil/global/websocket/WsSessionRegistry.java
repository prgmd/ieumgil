package com.ssafy.ieumgil.global.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * memberId ↔ WS 세션 매핑 (단일 인스턴스 전제).
 *
 * 용도:
 * - presence(Step 5): 접속/이탈 판정의 근거
 * - 강제 종료: 회원 탈퇴·그룹 탈퇴 시 해당 멤버 세션을 끊는다(GRP-09)
 *
 * 한 멤버가 탭 여러 개를 열 수 있으므로 세션은 Set으로 관리한다.
 *
 * <p><b>왜 전송 세션까지 들고 있나</b>: 인가 캐시만 비워서는 이미 성립한 구독을 막지 못한다.
 * 브로커가 구독자에게 밀어주는 경로는 인바운드 인터셉터를 타지 않기 때문이다. 실제로 끊으려면
 * WebSocketSession 자체를 닫아야 해서, 핸들러 데코레이터가 넘겨준 전송 세션을 여기 보관한다
 * (WebSocketConfig).
 */
@Slf4j
@Component
public class WsSessionRegistry {

    private final Map<Long, Set<String>> sessionsByMember = new ConcurrentHashMap<>();
    private final Map<String, Long> memberBySession = new ConcurrentHashMap<>();

    /** sessionId → 전송 세션. WebSocketSession.getId()가 곧 STOMP 세션 id다 */
    private final Map<String, WebSocketSession> transportBySession = new ConcurrentHashMap<>();

    public void register(Long memberId, String sessionId) {
        sessionsByMember.computeIfAbsent(memberId, id -> ConcurrentHashMap.newKeySet()).add(sessionId);
        memberBySession.put(sessionId, memberId);
    }

    /** @return 이 멤버의 마지막 세션이 끊겼으면 true (presence off 판정용) */
    public boolean unregister(String sessionId) {
        Long memberId = memberBySession.remove(sessionId);
        transportBySession.remove(sessionId);
        if (memberId == null) {
            return false;
        }
        Set<String> sessions = sessionsByMember.get(memberId);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                sessionsByMember.remove(memberId);
                return true;
            }
        }
        return false;
    }

    /**
     * 전송 세션 등록. STOMP CONNECT보다 먼저 일어나므로 이 시점엔 주인이 누구인지 모른다 —
     * memberId 매핑은 CONNECT 완료 이벤트에서 register()가 따로 채운다.
     */
    public void bindTransport(WebSocketSession session) {
        transportBySession.put(session.getId(), session);
    }

    public void unbindTransport(String sessionId) {
        transportBySession.remove(sessionId);
    }

    public Long memberOf(String sessionId) {
        return memberBySession.get(sessionId);
    }

    public boolean isOnline(Long memberId) {
        Set<String> sessions = sessionsByMember.get(memberId);
        return sessions != null && !sessions.isEmpty();
    }

    /**
     * 이 멤버의 WS 세션을 전부 끊는다 (그룹 탈퇴·회원 탈퇴 — GRP-09).
     *
     * <p>탈퇴한 그룹의 세션만이 아니라 <b>이 멤버의 모든 세션</b>을 끊는다. 세션이 어느
     * 프로젝트를 보고 있는지 여기서는 알 수 없고, 애매하면 끊는 쪽이 안전하기 때문이다.
     * 클라이언트는 곧바로 재연결하며 아직 소속된 그룹은 그때 다시 인가받는다 — 잠깐 끊길 뿐
     * 기능 손실은 없다.
     *
     * <p>매핑 정리(unregister)는 여기서 하지 않는다. close()가 SessionDisconnectEvent를
     * 발생시켜 기존 이탈 처리 경로가 presence까지 함께 정리하는데, 여기서 먼저 지우면
     * 그 경로가 세션의 주인을 찾지 못해 presence off가 누락된다.
     */
    public void disconnect(Long memberId) {
        Set<String> sessions = sessionsByMember.get(memberId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        // close()가 콜백으로 이 맵들을 건드리므로 복사본을 순회한다
        Set.copyOf(sessions).forEach(sessionId -> {
            WebSocketSession session = transportBySession.get(sessionId);
            if (session == null) {
                return;
            }
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
                log.info("WS 강제 종료: member={} session={}", memberId, sessionId);
            } catch (IOException | IllegalStateException e) {
                // 이미 닫히는 중이면 목적은 달성된 것이다 — 나머지 세션을 계속 닫는다
                log.warn("WS 강제 종료 실패: member={} session={} ({})", memberId, sessionId, e.getMessage());
            }
        });
    }
}
