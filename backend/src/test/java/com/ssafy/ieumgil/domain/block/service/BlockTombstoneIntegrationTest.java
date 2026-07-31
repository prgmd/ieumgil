package com.ssafy.ieumgil.domain.block.service;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tombstone 규약 — "없었다(404)"와 "지워졌다(410)"의 구분 (BLK-09).
 * 410이어야 프론트가 지연 도착한 op의 대상 블록만 조용히 화면에서 제거할 수 있다.
 */
class BlockTombstoneIntegrationTest extends IntegrationTestSupport {

    @Autowired
    BlockCommandService blockCommandService;
    @Autowired
    BlockRepository blockRepository;

    User user;
    Project project;
    long blockId;

    @BeforeEach
    void seed() {
        user = seedUser();
        project = seedProject(user);
        blockId = blockCommandService.createBlock(user.getId(), project.getId(), null,
                new BlockReqDTO.Create(BlockCategory.ETC, "삭제 대상", null, "a0", null, null,
                        null, null, null, null, null, null, null, null, null,
                        BlockSource.MANUAL, null))
                .blockId();
    }

    @Test
    @DisplayName("소프트 삭제된 블록은 행이 남고(tombstone) 체인 조회에서만 빠진다")
    void softDeleteKeepsRow() {
        blockCommandService.softDelete(user.getId(), blockId, null);

        assertThat(blockRepository.findById(blockId)).isPresent();   // 행은 남는다
        assertThat(blockRepository.findById(blockId).orElseThrow().isDeleted()).isTrue();
        assertThat(blockRepository.findChain(project.getId())).isEmpty();   // 산 블록 조회에서는 빠진다
    }

    @Test
    @DisplayName("삭제된 블록에 대한 수정/이동/중복 삭제는 전부 410(BLOCK_GONE)")
    void operationsOnTombstoneReturnGone() {
        blockCommandService.softDelete(user.getId(), blockId, null);

        assertThatThrownBy(() -> blockCommandService.updateFields(user.getId(), blockId, null,
                new BlockReqDTO.UpdateFields(List.of(new BlockReqDTO.FieldChange("budget", 1000)))))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.BLOCK_GONE);

        assertThatThrownBy(() -> blockCommandService.move(user.getId(), blockId, null,
                new BlockReqDTO.Move(2, "b0")))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.BLOCK_GONE);

        assertThatThrownBy(() -> blockCommandService.softDelete(user.getId(), blockId, null))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.BLOCK_GONE);
    }

    @Test
    @DisplayName("존재한 적 없는 블록은 404(BLOCK_NOT_FOUND) — 410과 구분된다")
    void unknownBlockReturnsNotFound() {
        assertThatThrownBy(() -> blockCommandService.softDelete(user.getId(), 9_999_999L, null))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.BLOCK_NOT_FOUND);
    }
}
