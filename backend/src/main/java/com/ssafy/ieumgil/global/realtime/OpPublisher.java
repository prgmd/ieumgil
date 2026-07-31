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
 * 채번~기록 구간은 프로젝트 단위 ReentrantLock으로 직렬화한다(단일 인스턴스 전제).
 * 락이 커밋까지 잡지는 않으므로 seq 5가 6보다 늦게 커밋될 수 있다 — 클라이언트는
 * seq 갭을 감지하면 ops?afterSeq= 재전송으로 따라잡는 규약이라 순서 역전은 허용된다.
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
        try {
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
            lock.unlock();
        }
    }
}
