package com.ssafy.ieumgil.global.realtime;

import java.util.Map;

/**
 * op를 실시간 채널로 내보내는 출구.
 *
 * Step 2에서는 NoOpBroadcaster(아무것도 안 함)가 꽂혀 있고,
 * Step 3에서 STOMP 구현(SimpMessagingTemplate → /topic/project/{id})으로 교체된다.
 * 호출부(OpPublisher)는 이 인터페이스만 알므로 교체 시 코드 변경이 없다.
 */
public interface OpBroadcaster {

    /** @param op activity_log에 저장된 op 전문과 동일한 맵 — {seq, type, actorId, clientId, payload} */
    void send(Long projectId, Map<String, Object> op);
}
