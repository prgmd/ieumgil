package com.ssafy.ieumgil.global.realtime;

import java.util.Map;

/**
 * "op가 저장됐으니 커밋되면 내보내라"는 신호.
 * OpPublisher가 트랜잭션 안에서 발행하고, OpBroadcastListener가 커밋 후에 받는다.
 */
public record OpEvent(Long projectId, Map<String, Object> op) {
}
