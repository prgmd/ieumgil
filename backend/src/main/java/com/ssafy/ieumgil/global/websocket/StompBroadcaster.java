package com.ssafy.ieumgil.global.websocket;

import com.ssafy.ieumgil.global.realtime.OpBroadcaster;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * OpBroadcaster의 실구현 (Step 3) — NoOpBroadcaster를 @Primary로 대체한다.
 *
 * op는 activity_log 저장 전문과 동일한 맵 그대로 나간다. 요청자 본인 스킵은
 * 서버가 하지 않는다 — op의 clientId를 보고 수신 측(프론트)이 무시한다.
 * 서버가 걸러내려면 세션별 발송 제어가 필요해져 Simple Broker로는 비용이 크고,
 * 클라이언트 필터링은 코드 한 줄이다.
 */
@Component
@Primary
@RequiredArgsConstructor
public class StompBroadcaster implements OpBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void send(Long projectId, Map<String, Object> op) {
        messagingTemplate.convertAndSend("/topic/project/" + projectId, op);
    }
}
