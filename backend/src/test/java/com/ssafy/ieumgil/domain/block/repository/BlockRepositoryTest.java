package com.ssafy.ieumgil.domain.block.repository;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * findAllByIdInAndProject_IdAndDeletedAtIsNull — 교통 후보 계산이 blockId 목록을
 * 좌표로 바꿀 때 쓴다. 프로젝트 조건을 같이 걸어 소유권까지 확인하는 것이 핵심 —
 * 컨트롤러의 @GroupMember는 "이 프로젝트 멤버인가"까지만 보고 "이 블록이 그 프로젝트
 * 것인가"는 보지 않아서, 남의 프로젝트 블록 id를 섞어 보내는 경로를 여기서 막는다.
 */
class BlockRepositoryTest extends IntegrationTestSupport {

    @Autowired
    BlockRepository blockRepository;

    User author;
    Project projectA;
    Project projectB;

    @BeforeEach
    void seed() {
        author = seedUser();
        projectA = seedProject(author);
        projectB = seedProject(author);
    }

    private Block blockOf(Project project, String name) {
        return Block.builder()
                .name(name)
                .category(BlockCategory.ETC)
                .orderKey("a0")
                .source(BlockSource.MANUAL)
                .project(project)
                .author(author)
                .build();
    }

    @Test
    @DisplayName("다른 프로젝트의 블록 id는 조회되지 않는다 — 소유권 검증의 근거")
    void doesNotReturnBlocksOfAnotherProject() {
        // given: projectA에 블록 1개, projectB에 블록 1개
        Block mine = blockRepository.save(blockOf(projectA, "내 블록"));
        Block others = blockRepository.save(blockOf(projectB, "남의 블록"));

        // when: 두 id를 모두 넣고 projectA로 조회
        List<Block> found = blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(
                List.of(mine.getId(), others.getId()), projectA.getId());

        // then: 내 것만 나온다
        assertThat(found).extracting(Block::getId).containsExactly(mine.getId());
    }

    @Test
    @DisplayName("빈 id 목록으로 조회하면 빈 목록을 반환한다 — blockIds 0개 경로가 실 PG에서도 동작한다")
    void emptyIdListReturnsEmptyList() {
        blockRepository.save(blockOf(projectA, "내 블록"));

        List<Block> found = blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(
                List.of(), projectA.getId());

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("삭제된 블록은 조회되지 않는다")
    void doesNotReturnDeletedBlocks() {
        Block block = blockRepository.save(blockOf(projectA, "지울 블록"));
        block.softDelete();
        blockRepository.saveAndFlush(block);

        List<Block> found = blockRepository.findAllByIdInAndProject_IdAndDeletedAtIsNull(
                List.of(block.getId()), projectA.getId());

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findGroupIdById — 살아있는 프로젝트의 블록은 groupId를 돌려준다(tombstone 블록 포함)")
    void findGroupIdReturnsGroupForAliveProject() {
        Block alive = blockRepository.save(blockOf(projectA, "산 블록"));
        Block tombstone = blockRepository.save(blockOf(projectA, "지운 블록"));
        tombstone.softDelete();
        blockRepository.saveAndFlush(tombstone);

        Long expected = projectA.getTravelGroup().getId();
        assertThat(blockRepository.findGroupIdById(alive.getId())).contains(expected);
        // tombstone 블록의 지연 op는 410 판정을 위해 인가를 통과해야 하므로 groupId가 나온다
        assertThat(blockRepository.findGroupIdById(tombstone.getId())).contains(expected);
    }

    @Test
    @DisplayName("findGroupIdById — 소프트삭제된 프로젝트의 블록은 groupId를 돌려주지 않는다")
    void findGroupIdSkipsSoftDeletedProject() {
        Block block = blockRepository.save(blockOf(projectA, "삭제될 프로젝트의 블록"));
        projectA.softDelete();
        projectRepository.saveAndFlush(projectA);

        assertThat(blockRepository.findGroupIdById(block.getId())).isEmpty();
    }
}
