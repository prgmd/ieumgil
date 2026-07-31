package com.ssafy.ieumgil.global.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * op 브로드캐스트는 반드시 커밋 이후(AFTER_COMMIT)에만 나간다.
 *
 * 커밋 전에 내보내면: 수신자가 아직 DB에 없는 상태를 화면에 그리고, 그 직후 트랜잭션이
 * 롤백되면 어디에도 없는 "유령 블록"이 그 클라이언트에만 남는다. 재현이 산발적이고
 * 서버 로그에도 안 남는 부류의 버그라, 발생 후 잡는 것보다 구조로 막는 쪽이 압도적으로 싸다.
 *
 * 반대로 activity_log INSERT는 본 트랜잭션 안에 있어야 한다(OpPublisher) —
 * 변경과 저널이 원자적으로 묶여야 "재전송 = 저장된 걸 그대로 쏜다"가 성립한다.
 * 트랜잭션 밖으로 빼는 것은 오직 전송뿐이다.
 */
@Component
@RequiredArgsConstructor
public class OpBroadcastListener {

    private final OpBroadcaster broadcaster;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOp(OpEvent event) {
        broadcaster.send(event.projectId(), event.op());
    }
}
