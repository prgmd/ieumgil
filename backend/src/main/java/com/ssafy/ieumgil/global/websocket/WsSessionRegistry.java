package com.ssafy.ieumgil.global.websocket;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * memberId ↔ WS 세션 매핑 (단일 인스턴스 전제).
 *
 * 용도:
 * - presence(Step 5): 접속/이탈 판정의 근거
 * - 강제 종료: 회원 탈퇴·그룹 탈퇴 시 해당 멤버 세션을 끊거나 멤버십 캐시를 무효화
 *
 * 한 멤버가 탭 여러 개를 열 수 있으므로 세션은 Set으로 관리한다.
 */
@Component
public class WsSessionRegistry {

    private final Map<Long, Set<String>> sessionsByMember = new ConcurrentHashMap<>();
    private final Map<String, Long> memberBySession = new ConcurrentHashMap<>();

    public void register(Long memberId, String sessionId) {
        sessionsByMember.computeIfAbsent(memberId, id -> ConcurrentHashMap.newKeySet()).add(sessionId);
        memberBySession.put(sessionId, memberId);
    }

    /** @return 이 멤버의 마지막 세션이 끊겼으면 true (presence off 판정용) */
    public boolean unregister(String sessionId) {
        Long memberId = memberBySession.remove(sessionId);
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

    public Long memberOf(String sessionId) {
        return memberBySession.get(sessionId);
    }

    public boolean isOnline(Long memberId) {
        Set<String> sessions = sessionsByMember.get(memberId);
        return sessions != null && !sessions.isEmpty();
    }
}
