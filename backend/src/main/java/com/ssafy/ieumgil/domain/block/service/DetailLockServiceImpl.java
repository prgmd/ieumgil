package com.ssafy.ieumgil.domain.block.service;

import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.block.exception.BlockErrorCode;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 세부 내용(detail) 텍스트 편집 락 (BLK-08) — Redis SET NX + TTL 30초.
 *
 * detail만 락을 거는 이유: 자유 텍스트는 LWW로 합치면 한쪽 문장이 통째로 사라진다.
 * 숫자·시각 필드는 필드 단위 LWW(BLK-05)로 충분하지만 문장은 "한 명씩 쓰기"가 맞다.
 *
 * TTL 30초 / 하트비트 10초 주기 = 3회 연속 유실돼야 만료 — 네트워크 순단에 관대하면서도
 * 브라우저가 죽으면 최대 30초 안에 남이 이어서 편집할 수 있다.
 *
 * 락은 RDB에 저장하지 않는다(설계 결정) — 서버가 죽으면 락도 함께 사라지는 편이
 * "영원히 잠긴 블록"보다 낫다.
 */
@Service
@RequiredArgsConstructor
public class DetailLockServiceImpl implements DetailLockService {

    private static final String KEY_PREFIX = "lock:block:";
    private static final Duration LOCK_TTL = Duration.ofSeconds(30);

    /** 소유자 확인과 삭제를 원자적으로 — GET 후 DEL 사이에 만료·재획득이 끼어들 수 있다 */
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final BlockRepository blockRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public BlockResDTO.LockResult acquire(Long userId, Long blockId) {
        String key = KEY_PREFIX + blockId;

        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, String.valueOf(userId), LOCK_TTL);

        if (Boolean.TRUE.equals(acquired)) {
            broadcastBadge(blockId, userId, true);
            return BlockResDTO.LockResult.builder()
                    .acquired(true).holder(userId).ttlRemaining(LOCK_TTL.toSeconds()).build();
        }

        // 실패 응답에 holder·잔여 TTL을 실어 클라이언트 재시도 주기의 근거를 준다
        String holder = redisTemplate.opsForValue().get(key);
        Long ttl = redisTemplate.getExpire(key);
        return BlockResDTO.LockResult.builder()
                .acquired(false)
                .holder(holder != null ? Long.parseLong(holder) : null)
                .ttlRemaining(ttl != null && ttl > 0 ? ttl : 0)
                .build();
    }

    @Override
    public BlockResDTO.LockHeartbeat heartbeat(Long userId, Long blockId) {
        String key = KEY_PREFIX + blockId;

        // 하트비트는 소유자만 — 남의 락 TTL을 연장해 주면 락 탈취 방어가 무의미해진다
        String holder = redisTemplate.opsForValue().get(key);
        if (holder == null || !holder.equals(String.valueOf(userId))) {
            throw new CustomException(BlockErrorCode.LOCK_NOT_HELD);
        }
        redisTemplate.expire(key, LOCK_TTL);
        return BlockResDTO.LockHeartbeat.builder().ttlRemaining(LOCK_TTL.toSeconds()).build();
    }

    @Override
    public boolean isLockedByOther(Long userId, Long blockId) {
        String holder = redisTemplate.opsForValue().get(KEY_PREFIX + blockId);
        return holder != null && !holder.equals(String.valueOf(userId));
    }

    @Override
    public void release(Long userId, Long blockId) {
        Long deleted = redisTemplate.execute(RELEASE_IF_OWNER,
                List.of(KEY_PREFIX + blockId), String.valueOf(userId));

        // 이미 만료됐거나 남의 락이면 조용히 무시한다 — 해제는 멱등이어야
        // "만료 직후 해제 요청"이라는 흔한 순서가 에러가 되지 않는다
        if (deleted != null && deleted > 0) {
            broadcastBadge(blockId, userId, false);
        }
    }

    /** 편집 배지 on/off — presence 토픽으로 전파. seq 없음(순간 정보, 스냅샷 복구 대상 아님) */
    private void broadcastBadge(Long blockId, Long userId, boolean locked) {
        blockRepository.findProjectIdById(blockId).ifPresent(projectId ->
                messagingTemplate.convertAndSend("/topic/project/" + projectId + "/presence",
                        Map.of("type", "DETAIL_LOCK", "blockId", blockId,
                                "memberId", userId, "locked", locked)));
    }
}
