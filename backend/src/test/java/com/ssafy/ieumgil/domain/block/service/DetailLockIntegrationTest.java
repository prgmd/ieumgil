package com.ssafy.ieumgil.domain.block.service;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.exception.BlockErrorCode;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * detail-lock 의미론 — 세부 내용(긴 텍스트)을 지키는 유일한 장치의 회귀 테스트.
 *
 * 긴 텍스트는 LWW로 합치면 한쪽 문단이 통째로 사라지므로 "한 명씩 쓰기"가 규약이다.
 * 실제 Redis(Testcontainers)에 서비스 계층을 직접 호출한다 — SET NX와 Lua 스크립트의
 * 원자성은 목으로 검증할 수 없다.
 */
class DetailLockIntegrationTest extends IntegrationTestSupport {

    private static final String KEY_PREFIX = "lock:block:";   // DetailLockServiceImpl과 동일 — 만료 시뮬레이션용

    @Autowired
    DetailLockService detailLockService;
    @Autowired
    BlockCommandService blockCommandService;
    @Autowired
    BlockRepository blockRepository;
    @Autowired
    StringRedisTemplate redisTemplate;

    User userA;
    User userB;
    long blockId;

    @BeforeEach
    void seed() {
        userA = seedUser();
        userB = seedUser();
        Project project = seedProject(userA);
        blockId = blockRepository.save(Block.builder()
                .name("락 대상")
                .category(BlockCategory.ETC)
                .orderKey("a0")
                .source(BlockSource.MANUAL)
                .project(project)
                .author(userA)
                .build()).getId();
    }

    @Test
    @DisplayName("경합 시 한 명만 획득하고, 진 쪽은 holder와 잔여 TTL을 받는다")
    void onlyOneAcquiresUnderContention() {
        BlockResDTO.LockResult first = detailLockService.acquire(userA.getId(), blockId);
        BlockResDTO.LockResult second = detailLockService.acquire(userB.getId(), blockId);

        assertThat(first.acquired()).isTrue();
        assertThat(first.holder()).isEqualTo(userA.getId());

        assertThat(second.acquired()).isFalse();
        assertThat(second.holder()).isEqualTo(userA.getId());   // 누가 잡고 있는지 알려줘야 배지를 그린다
        assertThat(second.ttlRemaining()).isPositive();          // 재시도 주기의 근거
    }

    @Test
    @DisplayName("남의 락은 해제할 수 없다 — Lua 소유자 확인이 지켜준다")
    void cannotReleaseOthersLock() {
        detailLockService.acquire(userA.getId(), blockId);

        detailLockService.release(userB.getId(), blockId);   // B가 A의 락 해제 시도 — 조용히 무시

        // 락이 여전히 A 소유인지: B의 획득 시도가 계속 실패해야 한다
        assertThat(detailLockService.acquire(userB.getId(), blockId).acquired()).isFalse();
    }

    @Test
    @DisplayName("소유자가 해제하면 다음 사람이 즉시 획득할 수 있다")
    void ownerReleaseFreesLock() {
        detailLockService.acquire(userA.getId(), blockId);

        detailLockService.release(userA.getId(), blockId);

        assertThat(detailLockService.acquire(userB.getId(), blockId).acquired()).isTrue();
    }

    @Test
    @DisplayName("해제는 멱등 — 만료 직후의 해제 요청이 에러가 되지 않는다")
    void releaseIsIdempotent() {
        detailLockService.acquire(userA.getId(), blockId);
        detailLockService.release(userA.getId(), blockId);

        assertThatCode(() -> detailLockService.release(userA.getId(), blockId))
                .doesNotThrowAnyException();
        assertThatCode(() -> detailLockService.release(userB.getId(), 424242L))
                .doesNotThrowAnyException();   // 애초에 락이 없던 블록도 마찬가지
    }

    @Test
    @DisplayName("하트비트는 소유자만 — 남의 락 TTL을 연장해 주면 탈취 방어가 무의미해진다")
    void heartbeatOnlyForOwner() {
        detailLockService.acquire(userA.getId(), blockId);

        BlockResDTO.LockHeartbeat beat = detailLockService.heartbeat(userA.getId(), blockId);
        assertThat(beat.ttlRemaining()).isEqualTo(30);

        assertThatThrownBy(() -> detailLockService.heartbeat(userB.getId(), blockId))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.LOCK_NOT_HELD);
    }

    @Test
    @DisplayName("만료된 락에는 하트비트가 거부되고, 다른 사람이 획득할 수 있다")
    void expiredLockRejectsHeartbeatAndFreesSlot() {
        detailLockService.acquire(userA.getId(), blockId);

        // TTL 30초를 기다리는 대신 키 삭제로 만료를 시뮬레이션한다(효과 동일 — 키 부재)
        redisTemplate.delete(KEY_PREFIX + blockId);

        assertThatThrownBy(() -> detailLockService.heartbeat(userA.getId(), blockId))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.LOCK_NOT_HELD);
        assertThat(detailLockService.acquire(userB.getId(), blockId).acquired()).isTrue();
    }

    @Test
    @DisplayName("획득 시 TTL 30초가 걸린다 — 브라우저가 죽어도 최대 30초 뒤 남이 이어서 편집")
    void lockHasTtl() {
        detailLockService.acquire(userA.getId(), blockId);

        Long ttl = redisTemplate.getExpire(KEY_PREFIX + blockId);
        assertThat(ttl).isBetween(1L, 30L);   // 걸려는 있되 30초 이하
    }

    // ----- detail 쓰기 강제 (BLK-08 서버 측) — 락을 클라이언트 예의에만 맡기지 않는다 -----

    @Test
    @DisplayName("남이 락을 쥔 블록의 detail 쓰기는 409로 거부된다")
    void detailWriteRejectedWhileLockedByOther() {
        detailLockService.acquire(userA.getId(), blockId);

        assertThatThrownBy(() -> patchField(userB.getId(), "detail", "B가 덮어쓰기 시도"))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.DETAIL_LOCKED_BY_OTHER);
    }

    @Test
    @DisplayName("락 소유자 본인의 detail 쓰기는 통과한다")
    void detailWriteAllowedForLockHolder() {
        detailLockService.acquire(userA.getId(), blockId);

        assertThatCode(() -> patchField(userA.getId(), "detail", "소유자의 편집"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("남이 락을 쥐어도 detail 아닌 필드는 통과한다 — 락의 보호 대상은 detail뿐")
    void nonDetailFieldsUnaffectedByLock() {
        detailLockService.acquire(userA.getId(), blockId);

        assertThatCode(() -> patchField(userB.getId(), "name", "이름은 LWW 영역"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("락이 없으면 detail 쓰기는 통과한다 — 획득은 편집 UX 절차일 뿐 쓰기 전제조건이 아니다")
    void detailWriteAllowedWithoutLock() {
        assertThatCode(() -> patchField(userB.getId(), "detail", "락 없는 편집"))
                .doesNotThrowAnyException();
    }

    private void patchField(Long userId, String field, Object value) {
        blockCommandService.updateFields(userId, blockId, null,
                new BlockReqDTO.UpdateFields(List.of(new BlockReqDTO.FieldChange(field, value))));
    }
}
