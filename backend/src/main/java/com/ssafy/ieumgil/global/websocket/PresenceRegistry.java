package com.ssafy.ieumgil.global.websocket;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프로젝트별 "지금 대시보드를 보고 있는 멤버" (단일 인스턴스, 인메모리).
 *
 * 판정 기준은 메인 op 토픽(/topic/project/{id}) 구독이다 — 구독 중 = 보드를 보는 중.
 * 한 멤버가 탭 여러 개로 같은 보드를 볼 수 있어 세션 단위로 센다.
 * 다중 인스턴스로 가면 이 클래스만 Redis(TTL 키) 구현으로 교체한다.
 */
@Component
public class PresenceRegistry {

    /** projectId → (memberId → sessionIds) */
    private final Map<Long, Map<Long, Set<String>>> viewers = new ConcurrentHashMap<>();

    /** @return 이 멤버가 이 프로젝트에 "처음" 나타났으면 true (online 브로드캐스트 필요) */
    public boolean enter(Long projectId, Long memberId, String sessionId) {
        Map<Long, Set<String>> members = viewers.computeIfAbsent(projectId, id -> new ConcurrentHashMap<>());
        Set<String> sessions = members.computeIfAbsent(memberId, id -> ConcurrentHashMap.newKeySet());
        boolean first = sessions.isEmpty();
        sessions.add(sessionId);
        return first;
    }

    /** @return 이 멤버의 마지막 세션이 떠났으면 true (offline 브로드캐스트 필요) */
    public boolean leave(Long projectId, Long memberId, String sessionId) {
        Map<Long, Set<String>> members = viewers.get(projectId);
        if (members == null) {
            return false;
        }
        Set<String> sessions = members.get(memberId);
        if (sessions == null) {
            return false;
        }
        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            members.remove(memberId);
            return true;
        }
        return false;
    }

    public Set<Long> onlineMembers(Long projectId) {
        Map<Long, Set<String>> members = viewers.get(projectId);
        return members == null ? Set.of() : Set.copyOf(members.keySet());
    }
}
