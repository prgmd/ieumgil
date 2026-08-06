package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기간 축소 시 범위 밖 블록의 POOL 이동(PRJ-02) — 벌크 UPDATE 순서 함정의 회귀 테스트.
 *
 * 함정: @Modifying(clearAutomatically) 벌크 UPDATE는 영속성 컨텍스트를 비우므로,
 * 순서를 바꿔 벌크를 먼저 부르면 project 이름 변경 같은 엔티티 수정이 조용히 증발한다.
 * 지금 구현은 [엔티티 변경 → SELECT id → 벌크 마지막] 순서를 지킨다 — 이 테스트가 그 감시자다.
 */
class ProjectBoardIntegrationTest extends IntegrationTestSupport {

    @Autowired
    ProjectCommandService projectCommandService;
    @Autowired
    BlockRepository blockRepository;
    @Autowired
    ActivityLogRepository activityLogRepository;

    private Project seedProjectWithDates(User member, LocalDate start, LocalDate end) {
        TravelGroup group = travelGroupRepository.save(TravelGroup.builder()
                .name("기간 그룹")
                .inviteCode(UUID.randomUUID().toString().substring(0, 8))
                .inviteExpiresAt(LocalDateTime.now().plusDays(7))
                .build());
        groupMemberRepository.save(GroupMember.builder().travelGroup(group).user(member).build());
        return projectRepository.save(Project.builder()
                .name("기간 테스트 여행")
                .travelGroup(group)
                .startDate(start)
                .endDate(end)
                .build());
    }

    /** dayNo는 Day 1 00:00 기준 오프셋으로도 함께 심는다 — 범위 판정이 읽는 쪽은 오프셋이다 */
    private Block seedBlock(Project project, User author, Integer dayNo, String orderKey) {
        return blockRepository.save(Block.builder()
                .name("블록 day" + dayNo)
                .category(BlockCategory.ETC)
                .orderKey(orderKey)
                .source(BlockSource.MANUAL)
                .dayNo(dayNo)
                .startOffsetMinutes(dayNo == null ? null : (dayNo - 1) * Block.MINUTES_PER_DAY)
                .project(project)
                .author(author)
                .build());
    }

    @Test
    @DisplayName("기간 축소 시 범위 밖 블록만 POOL로 이동하고, orderKey는 보존되며, 같은 요청의 이름 변경도 살아남는다")
    void shrinkingPeriodMovesOutOfRangeBlocksToPool() {
        User user = seedUser();
        Project project = seedProjectWithDates(user, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12));   // 3일
        Block day1 = seedBlock(project, user, 1, "a0");
        Block day3 = seedBlock(project, user, 3, "a1");
        Block pool = seedBlock(project, user, null, "a2");

        // 3일 → 1일로 축소 + 이름도 같이 변경 (벌크 순서 함정을 함께 검증)
        ProjectResDTO.Updated result = projectCommandService.updateProject(user.getId(), project.getId(), null,
                new ProjectReqDTO.Update("이름변경", null, LocalDate.of(2026, 8, 10), null, null));

        // 범위 밖(day3)만 이동 — day1과 기존 POOL 블록은 건드리지 않는다
        assertThat(result.movedToPool()).containsExactly(day3.getId());

        Block movedDay3 = blockRepository.findById(day3.getId()).orElseThrow();
        assertThat(movedDay3.getStartOffsetMinutes()).isNull();    // POOL로
        assertThat(movedDay3.getDayNo()).isNull();                 // 병행 기록도 함께 비운다
        assertThat(movedDay3.getOrderKey()).isEqualTo("a1");       // 정렬 키는 유지
        assertThat(blockRepository.findById(day1.getId()).orElseThrow().getDayNo()).isEqualTo(1);
        assertThat(blockRepository.findById(pool.getId()).orElseThrow().getDayNo()).isNull();

        // 벌크 UPDATE(clearAutomatically)가 마지막이었어야 살아남는 변경 — 순서가 바뀌면 여기서 깨진다
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getName()).isEqualTo("이름변경");

        // op payload에도 movedToPool이 실려 수신 측이 해당 블록만 옮겨 그릴 수 있다
        List<Map<String, Object>> ops = activityLogRepository.findOpsAfter(project.getId(), 0, Pageable.unpaged());
        Map<String, Object> lastOp = ops.get(ops.size() - 1);
        assertThat(lastOp.get("type")).isEqualTo("PROJECT_UPDATED");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) lastOp.get("payload");
        List<?> movedIds = (List<?>) payload.get("movedToPool");
        assertThat(movedIds).hasSize(1);
        assertThat(((Number) movedIds.get(0)).longValue()).isEqualTo(day3.getId());   // JSONB 숫자는 Integer로 돌아온다
    }

    @Test
    @DisplayName("총 예산 변경 — 값이 반영되고, 명시적 null은 예산 미설정으로 초기화되며 op에도 null이 실린다")
    void updateBudgetAppliesValueAndNullClearsIt() {
        User user = seedUser();
        Project project = seedProjectWithDates(user, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12));

        ProjectResDTO.BudgetChanged changed = projectCommandService.updateBudget(
                user.getId(), project.getId(), null, new ProjectReqDTO.UpdateBudget(300000));
        assertThat(changed.targetBudget()).isEqualTo(300000);
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTargetBudget()).isEqualTo(300000);

        // null = "예산 미설정으로 초기화" — 이 시맨틱이 바뀌면 여기서 깨진다
        ProjectResDTO.BudgetChanged cleared = projectCommandService.updateBudget(
                user.getId(), project.getId(), null, new ProjectReqDTO.UpdateBudget(null));
        assertThat(cleared.targetBudget()).isNull();
        assertThat(projectRepository.findById(project.getId()).orElseThrow().getTargetBudget()).isNull();

        List<Map<String, Object>> ops = activityLogRepository.findOpsAfter(project.getId(), 0, Pageable.unpaged());
        Map<String, Object> lastOp = ops.get(ops.size() - 1);
        assertThat(lastOp.get("type")).isEqualTo("TARGET_BUDGET_CHANGED");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) lastOp.get("payload");
        assertThat(payload).containsKey("targetBudget");
        assertThat(payload.get("targetBudget")).isNull();
    }

    @Test
    @DisplayName("기간 축소여도 범위 밖 블록이 없으면 movedToPool은 빈 목록이다")
    void noBlocksMovedWhenAllInRange() {
        User user = seedUser();
        Project project = seedProjectWithDates(user, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 12));
        seedBlock(project, user, 1, "a0");

        ProjectResDTO.Updated result = projectCommandService.updateProject(user.getId(), project.getId(), null,
                new ProjectReqDTO.Update(null, null, LocalDate.of(2026, 8, 10), null, null));

        assertThat(result.movedToPool()).isEmpty();
    }
}
