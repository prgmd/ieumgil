package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.ProjectStatus;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 프로젝트의 서로 다른 필드를 동시에 수정해도 전부 보존되는지 — 행 잠금(PESSIMISTIC_WRITE) 회귀 테스트.
 *
 * 배경: Block과 동일한 결함이 Project에 있었다 — 잠금 없는 조회 뒤 Hibernate 전체 컬럼
 * UPDATE가 마지막 커밋으로 앞선 커밋의 다른 필드를 덮는다(lost update). 프로젝트는 각
 * 변경의 op가 이미 저널에 남은 뒤라, 유실되면 저널↔DB가 어긋나 재연결 스냅샷과 실시간
 * 화면이 갈라진다. ProjectRepository.findByIdForUpdate의 @Lock을 제거하면 이 테스트가
 * (간헐적으로) 깨진다.
 */
class ProjectConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    ProjectCommandService projectCommandService;

    @Test
    @DisplayName("상태·예산·정산 인원·이름을 동시에 수정하면 4개 전부 보존된다")
    void concurrentDifferentFieldsAllPreserved() throws InterruptedException {
        User user = seedUser();
        Project project = seedProject(user);
        Long userId = user.getId();
        Long projectId = project.getId();

        Map<String, Runnable> changes = Map.of(
                "status", () -> projectCommandService.changeStatus(userId, projectId, null,
                        new ProjectReqDTO.UpdateStatus(ProjectStatus.DONE)),
                "targetBudget", () -> projectCommandService.updateBudget(userId, projectId, null,
                        new ProjectReqDTO.UpdateBudget(300000)),
                "budgetHeadcount", () -> projectCommandService.changeBudgetHeadcount(userId, projectId, null,
                        new ProjectReqDTO.UpdateHeadcount(3)),
                "name", () -> projectCommandService.updateProject(userId, projectId, null,
                        new ProjectReqDTO.Update("이름변경", null, null, null, null)));

        ExecutorService pool = Executors.newFixedThreadPool(changes.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(changes.size());
        Map<String, Boolean> succeeded = new ConcurrentHashMap<>();

        changes.forEach((field, command) -> pool.submit(() -> {
            try {
                start.await();
                command.run();
                succeeded.put(field, true);
            } catch (Exception e) {
                succeeded.put(field, false);
            } finally {
                done.countDown();
            }
        }));

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 응답이 전부 성공이라고 끝이 아니다 — 최종 상태에서 네 값이 전부 살아있어야 한다
        assertThat(succeeded).allSatisfy((field, ok) -> assertThat(ok).as(field).isTrue());

        Project after = projectRepository.findById(projectId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ProjectStatus.DONE);
        assertThat(after.getDoneAt()).isNotNull();
        assertThat(after.getTargetBudget()).isEqualTo(300000);
        assertThat(after.getBudgetHeadcount()).isEqualTo(3);
        assertThat(after.getName()).isEqualTo("이름변경");
    }
}
