package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.project.converter.ProjectConverter;
import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.global.realtime.OpPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.ssafy.ieumgil.global.realtime.OpPayloads.payloadWithNullable;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectCommandServiceImpl implements ProjectCommandService {

    private final ProjectRepository projectRepository;
    private final TravelGroupRepository travelGroupRepository;
    private final BlockRepository blockRepository;
    private final OpPublisher opPublisher;

    /** 프로젝트 생성 (PRJ-01). 대시보드가 아직 열리기 전이라 op는 발행하지 않는다 */
    @Override
    public ProjectResDTO.Created createProject(Long userId, Long groupId, ProjectReqDTO.Create request) {
        TravelGroup group = travelGroupRepository.findAliveByIdOrThrow(groupId);

        validateDateRange(request.startDate(), request.endDate());

        Project project = projectRepository.save(ProjectConverter.toProject(group, request));

        return ProjectConverter.toCreated(project);
    }

    /**
     * 프로젝트 이름·기간 수정 (PRJ-02). null인 필드는 건드리지 않는다.
     *
     * 기간이 줄어 범위를 벗어난 블록은 후보(POOL)로 일괄 이동하고 그 id 목록(movedToPool)을
     * 응답과 op에 싣는다 — 각 클라이언트가 해당 블록만 POOL 영역으로 옮겨 그린다.
     */
    @Override
    public ProjectResDTO.Updated updateProject(Long userId, Long projectId, String clientId,
                                               ProjectReqDTO.Update request) {
        Project project = getProject(projectId);

        // 부분 수정이라 안 보낸 필드는 기존 값과 비교해야 한다
        LocalDate startDate = request.startDate() != null ? request.startDate() : project.getStartDate();
        LocalDate endDate = request.endDate() != null ? request.endDate() : project.getEndDate();
        validateDateRange(startDate, endDate);

        project.updateInfo(request.name(), request.startDate(), request.endDate(),
                request.destination());
        project.changeTransportPref(request.transportPrefs());

        List<Long> movedToPool = moveOutOfRangeBlocksToPool(project, startDate, endDate);

        Map<String, Object> payload = payloadWithNullable(
                "projectId", projectId,
                "name", project.getName(),
                "startDate", project.getStartDate() != null ? project.getStartDate().toString() : null,
                "endDate", project.getEndDate() != null ? project.getEndDate().toString() : null,
                "destination", project.getDestination(),
                "movedToPool", movedToPool,
                "transportPrefs", project.getTransportPrefs() == null
                        ? List.of()
                        : project.getTransportPrefs().stream().map(Enum::name).toList());
        opPublisher.publish(projectId, userId, clientId, "PROJECT_UPDATED", payload);

        return ProjectConverter.toUpdated(project, movedToPool);
    }

    /** 프로젝트 소프트 삭제 (PRJ-03). 보고 있던 멤버가 목록으로 빠져나가도록 op를 쏜다 */
    @Override
    public void softDeleteProject(Long userId, Long projectId, String clientId) {
        Project project = getProject(projectId);

        // 변경을 publish보다 먼저 — publish가 락 획득 전에 flush하므로 락 순서가
        // "DB 행 락 → 프로젝트 락" 한 방향으로 유지된다(OpPublisher 규약: publish 뒤 엔티티 변경 금지).
        // 브로드캐스트는 커밋 이후에만 나가므로 롤백되면 op도 함께 사라진다는 성질은 그대로다.
        project.softDelete();

        opPublisher.publish(projectId, userId, clientId, "PROJECT_DELETED",
                Map.of("projectId", projectId));
    }

    /** 상태 전환 (GRP-10). PLANNING↔DONE 양방향, DONE이어도 쓰기 거부 없음(NFR-05) */
    @Override
    public ProjectResDTO.StatusChanged changeStatus(Long userId, Long projectId, String clientId,
                                                    ProjectReqDTO.UpdateStatus request) {
        Project project = getProject(projectId);

        project.changeStatus(request.status());

        Map<String, Object> payload = payloadWithNullable(
                "projectId", projectId,
                "status", project.getStatus().name(),
                "doneAt", project.getDoneAt() != null ? project.getDoneAt().toString() : null);
        opPublisher.publish(projectId, userId, clientId, "PROJECT_STATUS_CHANGED", payload);

        return ProjectResDTO.StatusChanged.builder()
                .status(project.getStatus())
                .doneAt(project.getDoneAt())
                .build();
    }

    /** 정산 인원 지정/해제 (BGT-03). null이면 그룹 멤버 수 연동으로 복귀 */
    @Override
    public ProjectResDTO.HeadcountChanged changeBudgetHeadcount(Long userId, Long projectId, String clientId,
                                                                ProjectReqDTO.UpdateHeadcount request) {
        Project project = getProject(projectId);

        project.changeBudgetHeadcount(request.headcount());

        Map<String, Object> payload = payloadWithNullable(
                "projectId", projectId,
                "budgetHeadcount", project.getBudgetHeadcount());   // null 그대로 — 연동 복귀 신호
        opPublisher.publish(projectId, userId, clientId, "BUDGET_HEADCOUNT_CHANGED", payload);

        return ProjectResDTO.HeadcountChanged.builder()
                .budgetHeadcount(project.getBudgetHeadcount())
                .build();
    }

    /** 총 예산 변경. null이면 예산 미설정으로 초기화한다 */
    @Override
    public ProjectResDTO.BudgetChanged updateBudget(Long userId, Long projectId, String clientId,
                                                    ProjectReqDTO.UpdateBudget request) {
        Project project = getProject(projectId);

        project.updateBudget(request.targetBudget());

        Map<String, Object> payload = payloadWithNullable(
                "projectId", projectId,
                "targetBudget", project.getTargetBudget());   // null 그대로 — 예산 해제 신호
        opPublisher.publish(projectId, userId, clientId, "TARGET_BUDGET_CHANGED", payload);

        return ProjectResDTO.BudgetChanged.builder()
                .targetBudget(project.getTargetBudget())
                .build();
    }

    // ----- 공통 -----

    /**
     * 기간 밖 블록의 POOL 이동. 응답·op에 실을 id는 벌크 UPDATE "전"에 SELECT로 확보한다
     * (벌크는 개수만 돌려준다). 벌크 UPDATE는 clearAutomatically로 영속성 컨텍스트를 비우므로
     * 반드시 마지막 순서로 부른다 — 앞의 project 변경은 flushAutomatically가 먼저 DB에 내보낸다.
     */
    private List<Long> moveOutOfRangeBlocksToPool(Project project, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            return List.of();   // 기간 미정이면 범위 개념이 없다
        }
        int dayCount = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        // 여행 전체 길이(분). 오프셋이 이 값이면 이미 마지막 Day의 다음 분이므로 범위 밖이다
        int tripMinutes = dayCount * Block.MINUTES_PER_DAY;

        List<Long> outOfRange = blockRepository.findIdsOutOfRange(project.getId(), tripMinutes);
        if (outOfRange.isEmpty()) {
            return List.of();
        }
        blockRepository.moveOutOfRangeToPool(project.getId(), tripMinutes);
        return outOfRange;
    }

    /** 존재 확인·멤버십은 컨트롤러의 @GroupMember가 끝냈다. 여기서는 엔티티만 가져온다 */
    private Project getProject(Long projectId) {
        return projectRepository.findAliveByIdOrThrow(projectId);
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new CustomException(ProjectErrorCode.INVALID_DATE_RANGE);
        }
    }
}
