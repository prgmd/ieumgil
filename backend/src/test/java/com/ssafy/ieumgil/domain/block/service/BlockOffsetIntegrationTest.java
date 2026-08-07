package com.ssafy.ieumgil.domain.block.service;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.exception.BlockErrorCode;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.service.ProjectCommandService;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.support.BlockFixtures;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 절대 오프셋 시각 모델(startOffsetMinutes 단일 컬럼)의 회귀 테스트.
 *
 * 옛 모델은 dayNo + start_time + end_time 세 컬럼이라 자정을 넘는 블록을 표현하지 못해
 * 두 행으로 쪼개야 했고, 위치가 세 값에 흩어져 서로 어긋날 수 있었다. 여기 있는 단정들이
 * 그 개편의 정의다 — 위치는 정수 하나, Day 번호·시각·자정 넘김은 전부 거기서 파생한다.
 *
 * 기간 축소 시 범위 밖 블록이 POOL로 내려가는 것은
 * ProjectBoardIntegrationTest가 이미 감시하므로 여기서 되풀이하지 않는다.
 */
class BlockOffsetIntegrationTest extends IntegrationTestSupport {

    @Autowired
    BlockCommandService blockCommandService;
    @Autowired
    ProjectCommandService projectCommandService;
    @Autowired
    BlockRepository blockRepository;

    private User member;
    private Project project;

    @BeforeEach
    void seedBoard() {
        member = seedUser();
        project = seedProjectWithDates(member, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 13));   // 4일
    }

    @Test
    @DisplayName("자정을 넘는 블록은 정확히 한 행으로 저장된다")
    void blockCrossingMidnightIsOneRow() {
        // Day 1 23:30 시작, 330분 → Day 2 05:00 종료
        Long blockId = blockCommandService.createBlock(member.getId(), project.getId(), null,
                BlockFixtures.at(BlockCategory.ETC, "심야 이동", "a0", 1410, 330)).blockId();

        Block saved = blockRepository.findById(blockId).orElseThrow();

        assertThat(blockRepository.findChain(project.getId())).hasSize(1);
        assertThat(saved.dayNo()).isEqualTo(1);
        // 자정에서 되감기지 않는다 — LocalTime 두 개로는 표현할 수 없던 값이다
        assertThat(saved.endOffsetMinutes()).isEqualTo(1740);
    }

    @Test
    @DisplayName("음수 오프셋은 저장되지 않는다")
    void negativeOffsetRejected() {
        assertThatThrownBy(() -> blockCommandService.createBlock(member.getId(), project.getId(), null,
                BlockFixtures.create(BlockCategory.ETC, "불가", "a0", -1)))
                // @Check가 만든 DB 제약 위반 — 애플리케이션 검증이 아니라 저장 자체가 거부된다
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("block_start_offset_minutes_check");

        assertThat(blockRepository.findChain(project.getId())).isEmpty();
    }

    @Test
    @DisplayName("오프셋은 필드 PATCH로 바꿀 수 없다 — 위치 변경은 position 하나뿐이다")
    void offsetIsNotAnLwwField() {
        Long blockId = blockCommandService.createBlock(member.getId(), project.getId(), null,
                BlockFixtures.at(BlockCategory.ETC, "장소", "a0", 600)).blockId();

        assertThatThrownBy(() -> blockCommandService.updateFields(member.getId(), blockId, null,
                new BlockReqDTO.UpdateFields(List.of(
                        new BlockReqDTO.FieldChange("startOffsetMinutes", 700)))))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.UNSUPPORTED_FIELD);

        assertThat(blockRepository.findById(blockId).orElseThrow().getStartOffsetMinutes()).isEqualTo(600);
    }

    @Test
    @DisplayName("시작일을 하루 미뤄도 블록 오프셋과 Day 번호는 그대로다")
    void shiftingStartDateKeepsOffsets() {
        // Day 2 00:30 — startDate를 옮겨도 원점은 Day 1이지 달력 날짜가 아니다
        Long blockId = blockCommandService.createBlock(member.getId(), project.getId(), null,
                BlockFixtures.at(BlockCategory.ETC, "장소", "a0", 1470)).blockId();

        projectCommandService.updateProject(member.getId(), project.getId(), null,
                new ProjectReqDTO.Update(null, project.getStartDate().plusDays(1), null, null, null));

        Block saved = blockRepository.findById(blockId).orElseThrow();
        assertThat(saved.getStartOffsetMinutes()).isEqualTo(1470);
        assertThat(saved.dayNo()).isEqualTo(2);
    }

    // ----- 시드 -----

    /** 기간이 있는 프로젝트 — 범위 판정이 startDate/endDate를 읽으므로 베이스의 seedProject로는 부족하다 */
    private Project seedProjectWithDates(User owner, LocalDate start, LocalDate end) {
        TravelGroup group = travelGroupRepository.save(TravelGroup.builder()
                .name("오프셋 그룹")
                .inviteCode(UUID.randomUUID().toString().substring(0, 8))
                .inviteExpiresAt(LocalDateTime.now().plusDays(7))
                .build());
        groupMemberRepository.save(GroupMember.builder().travelGroup(group).user(owner).build());
        return projectRepository.save(Project.builder()
                .name("오프셋 테스트 여행")
                .travelGroup(group)
                .startDate(start)
                .endDate(end)
                .build());
    }
}
