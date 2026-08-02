package com.ssafy.ieumgil.domain.block.service;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 블록의 서로 다른 필드를 동시에 수정해도 전부 보존되는지 — 행 잠금(PESSIMISTIC_WRITE) 회귀 테스트.
 *
 * 배경: 잠금 없는 1차 구현에서 응답은 5/5 전부 성공인데 실제로는 1/5만 보존됐다
 * (Hibernate 전체 컬럼 UPDATE가 마지막 커밋으로 앞선 커밋의 다른 필드를 덮음).
 * BlockRepository.findByIdForUpdate의 @Lock을 제거하면 이 테스트가 (간헐적으로) 깨진다.
 */
class BlockConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    BlockCommandService blockCommandService;
    @Autowired
    BlockRepository blockRepository;

    @Test
    @DisplayName("서로 다른 필드 5개를 동시에 수정하면 5개 전부 보존된다")
    void concurrentDifferentFieldsAllPreserved() throws InterruptedException {
        User user = seedUser();
        Project project = seedProject(user);
        long blockId = blockCommandService.createBlock(user.getId(), project.getId(), null,
                new BlockReqDTO.Create(BlockCategory.ETC, "동시성 대상", 1, "a0", null, null,
                        null, null, null, null, null, null, null, null, null,
                        BlockSource.MANUAL, null, null))
                .blockId();

        Map<String, Object> changes = Map.of(
                "name", "이름변경",
                "budget", 15000,
                "durationMin", 90,
                "detail", "동시 수정 메모",
                "isTimeFixed", true);

        ExecutorService pool = Executors.newFixedThreadPool(changes.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(changes.size());
        Map<String, Boolean> applied = new ConcurrentHashMap<>();

        changes.forEach((field, value) -> pool.submit(() -> {
            try {
                start.await();
                BlockResDTO.FieldsApplied result = blockCommandService.updateFields(
                        user.getId(), blockId, null,
                        new BlockReqDTO.UpdateFields(List.of(new BlockReqDTO.FieldChange(field, value))));
                applied.put(field, result.applied().get(field));
            } catch (Exception e) {
                applied.put(field, false);
            } finally {
                done.countDown();
            }
        }));

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 응답이 전부 성공이라고 끝이 아니다 — 최종 상태에서 다섯 값이 전부 살아있어야 한다
        assertThat(applied).allSatisfy((field, ok) -> assertThat(ok).as(field).isTrue());

        Block after = blockRepository.findById(blockId).orElseThrow();
        assertThat(after.getName()).isEqualTo("이름변경");
        assertThat(after.getBudget()).isEqualTo(15000);
        assertThat(after.getDurationMin()).isEqualTo(90);
        assertThat(after.getDetail()).isEqualTo("동시 수정 메모");
        assertThat(after.getIsTimeFixed()).isTrue();
        assertThat(after.getFieldUpdatedAt()).containsKeys(
                "name", "budget", "durationMin", "detail", "isTimeFixed");
    }
}
