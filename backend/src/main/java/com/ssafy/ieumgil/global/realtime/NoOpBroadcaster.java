package com.ssafy.ieumgil.global.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Step 3(WebSocket) 전까지의 자리 채움 구현.
 *
 * 브로드캐스트가 없어도 기능은 성립한다 — 변경은 REST 응답으로 요청자에게 돌아가고,
 * 다른 클라이언트는 새로고침(스냅샷) 또는 ops 재전송으로 따라잡는다(서버 권위 모델).
 */
@Slf4j
@Component
public class NoOpBroadcaster implements OpBroadcaster {

    @Override
    public void send(Long projectId, Map<String, Object> op) {
        log.debug("브로드캐스트 생략(Step 3 전): project={}, op={}", projectId, op.get("type"));
    }
}
