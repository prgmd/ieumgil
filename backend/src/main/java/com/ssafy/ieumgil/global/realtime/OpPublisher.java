package com.ssafy.ieumgil.global.realtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.activitylog.entity.ActivityLog;
import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 모든 변경 API가 재사용하는 op 파이프라인: seq 채번 → activity_log 기록 → (커밋 후) 브로드캐스트.
 *
 * 반드시 호출부의 트랜잭션 안에서 불러야 한다 — 변경(UPDATE/INSERT)과 저널 기록이
 * 한 트랜잭션으로 묶여야, 저널에는 있는데 변경은 롤백된(또는 그 반대) 어긋남이 생기지 않는다.
 * 브로드캐스트만 OpBroadcastListener가 AFTER_COMMIT으로 내보낸다.
 *
 * 채번~트랜잭션 완료(커밋/롤백) 구간을 프로젝트 단위 ReentrantLock으로 직렬화한다(단일 인스턴스 전제).
 * 락을 커밋 뒤까지 잡는 이유: 채번~기록까지만 잡으면 seq N이 미커밋인 채 N+1이 먼저 커밋될 수
 * 있고, 그 창에서 스냅샷을 읽은 클라이언트는 lastSeq=N+1인데 N의 변경이 없는 블록 상태를 받는다.
 * 이후 N의 브로드캐스트가 도착해도 seq ≤ lastSeq로 스킵되고, 같은 창에서는 ops?afterSeq=
 * 재조회도 N을 돌려주지 못해 새로고침 전까지 변경이 유실된다.
 *
 * 롤백된 seq는 저널에 영구 갭으로 남는다 — 이것은 순서 역전과 별개 문제로, 클라이언트의
 * 갭 재시도 규약이 다루는 영역이다.
 */
@Component
@RequiredArgsConstructor
public class OpPublisher {

    private final SeqGenerator seqGenerator;
    private final ActivityLogRepository activityLogRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, ReentrantLock> projectLocks = new ConcurrentHashMap<>();

    /**
     * @param clientId 요청 헤더 X-Client-Id — 수신 측이 "자기가 보낸 op"를 스킵하는 기준. 없으면 null
     * @param payload  op 종류별 본문 (예: BLOCK_MOVED → {blockId, dayNo, orderKey})
     * @return 채번된 seq — 생성/이동 응답에 실어 클라이언트가 자기 op의 위치를 알게 한다
     */
    public long publish(Long projectId, Long actorId, String clientId, String opType, Map<String, Object> payload) {
        ReentrantLock lock = projectLocks.computeIfAbsent(projectId, id -> new ReentrantLock());
        lock.lock();
        boolean unlockAtTxCompletion = false;
        try {
            // 트랜잭션 완료(커밋이든 롤백이든) 시점까지 락을 연장한다. 등록을 채번보다 먼저 하는
            // 이유: 등록 이후 어떤 예외가 나든 롤백의 afterCompletion이 반드시 unlock을 수행한다.
            // 같은 트랜잭션에서 publish가 여러 번 불려도 안전하다 — ReentrantLock의 hold count가
            // 호출 횟수만큼 쌓이고, 그만큼 등록된 동기화가 각각 한 번씩 unlock 한다.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        lock.unlock();
                    }
                });
                unlockAtTxCompletion = true;
            }

            long seq = seqGenerator.next(projectId);

            // 이 맵이 곧 브로드캐스트 전문이자 저장 전문이다 — 둘을 따로 만들면 언젠가 어긋난다
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("seq", seq);
            raw.put("type", opType);
            raw.put("actorId", actorId);
            raw.put("clientId", clientId);
            raw.put("payload", payload);

            // ⚠️ Jackson 왕복으로 정규화한 뒤 저장한다 — Hibernate가 JSON 컬럼의 변경을
            // 감지할 때 스냅샷을 "직렬화→역직렬화"로 딥카피하는데, 그 과정에서 Long(seq 등)이
            // Integer로 되살아나 equals 불일치 → INSERT 직후 매번 유령 UPDATE가 나갔다
            // (log_statement 실측으로 발견). 미리 같은 왕복을 거치면 스냅샷과 동형이 된다.
            Map<String, Object> op = objectMapper.convertValue(raw, new TypeReference<>() {
            });

            // getReferenceById: FK 값만 필요하므로 SELECT 없이 프록시로 참조만 건다
            activityLogRepository.save(ActivityLog.builder()
                    .opType(opType)
                    .payload(op)
                    .seq(seq)
                    .project(projectRepository.getReferenceById(projectId))
                    .user(userRepository.getReferenceById(actorId))
                    .build());

            eventPublisher.publishEvent(new OpEvent(projectId, op));
            return seq;
        } finally {
            // 트랜잭션 밖에서 불린 경우(규약 위반이지만 테스트 등)의 안전망 — 락을 여기서 푼다
            if (!unlockAtTxCompletion) {
                lock.unlock();
            }
        }
    }
}
