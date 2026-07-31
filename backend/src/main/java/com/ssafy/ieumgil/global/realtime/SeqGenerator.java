package com.ssafy.ieumgil.global.realtime;

import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 프로젝트별 op 시퀀스 채번 — Redis INCR (원자적).
 *
 * 리시드(reseed): Redis가 비워진 경우(재기동·flush) INCR이 1부터 다시 시작해
 * activity_log의 기존 seq와 충돌한다(UNIQUE 위반). 기동 시 전 프로젝트를 훑는 대신,
 * INCR 결과가 1인데 DB에 더 큰 seq가 있으면 그 자리에서 DB 기준으로 끌어올린다 —
 * 활동이 있는 프로젝트만 그때그때 보정되므로 스캔 비용이 없다.
 *
 * 호출부(OpPublisher)가 프로젝트 단위 락 안에서 부르므로 보정 구간의 동시성은 락이 보장한다.
 * (단일 인스턴스 전제 — 다중 인스턴스로 가면 이 클래스만 Redis 분산락 버전으로 교체한다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeqGenerator {

    private static final String KEY_PREFIX = "project:";
    private static final String KEY_SUFFIX = ":seq";

    private final StringRedisTemplate redisTemplate;
    private final ActivityLogRepository activityLogRepository;

    public long next(Long projectId) {
        String key = KEY_PREFIX + projectId + KEY_SUFFIX;
        Long value = redisTemplate.opsForValue().increment(key);

        // INCR 결과 1 = 방금 만들어진 키. Redis가 비워졌을 가능성이 있으니 DB와 대조한다.
        if (value != null && value == 1L) {
            long dbMax = activityLogRepository.findLastSeq(projectId);
            if (dbMax >= 1L) {
                long reseeded = dbMax + 1;
                redisTemplate.opsForValue().set(key, String.valueOf(reseeded));
                log.info("seq 리시드: project={} redis=1 → db 기준 {}", projectId, reseeded);
                return reseeded;
            }
        }
        return value != null ? value : 1L;
    }
}
