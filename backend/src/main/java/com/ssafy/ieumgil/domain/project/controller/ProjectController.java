package com.ssafy.ieumgil.domain.project.controller;

import com.ssafy.ieumgil.domain.activitylog.service.ActivityLogQueryService;
import com.ssafy.ieumgil.domain.group.annotation.GroupMember;
import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.service.ProjectCommandService;
import com.ssafy.ieumgil.domain.project.service.ProjectQueryService;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import com.ssafy.ieumgil.global.apiPayload.code.GeneralSuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 프로젝트는 경로가 두 갈래다 —
 * 목록·생성은 그룹 하위(/groups/{groupId}/projects), 수정·삭제는 단독(/projects/{projectId}).
 * 그래서 공통 접두사를 /api까지만 두고 메서드에서 나머지를 적는다.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "프로젝트 Controller")
public class ProjectController {

    private static final String CLIENT_ID_HEADER = "X-Client-Id";

    private final ProjectCommandService projectCommandService;
    private final ProjectQueryService projectQueryService;
    private final ActivityLogQueryService activityLogQueryService;

    @GroupMember
    @GetMapping("/groups/{groupId}/projects")
    @Operation(summary = "프로젝트 카드 목록 조회", description = "그룹의 프로젝트를 최근 생성 순으로 조회합니다.")
    public CustomResponse<List<ProjectResDTO.Card>> getGroupProjects(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long groupId) {
        return CustomResponse.onSuccess(projectQueryService.getGroupProjects(userId, groupId));
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @GetMapping("/projects/{projectId}")
    @Operation(summary = "대시보드 스냅샷 조회",
            description = "프로젝트 상세 + 블록 전체 + 멤버 + lastSeq를 한 번에 반환합니다. "
                    + "최초 로딩과 재연결 복구가 같은 응답을 씁니다. online은 presence 연동 전까지 false입니다.")
    public CustomResponse<ProjectResDTO.Snapshot> getSnapshot(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId) {
        return CustomResponse.onSuccess(projectQueryService.getSnapshot(userId, projectId));
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @GetMapping("/projects/{projectId}/ops")
    @Operation(summary = "유실 op 재전송",
            description = "afterSeq 이후의 op를 저장된 전문 그대로 순서대로 반환합니다. 재연결·seq 갭 감지 시 사용합니다.")
    public CustomResponse<List<Map<String, Object>>> getOps(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "0") long afterSeq) {
        // seq는 1부터 시작하므로 음수·누락은 0(처음부터)으로 하한을 건다
        long from = Math.max(0, afterSeq);
        return CustomResponse.onSuccess(activityLogQueryService.getOpsAfter(userId, projectId, from));
    }

    @GroupMember
    @PostMapping("/groups/{groupId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "프로젝트 생성", description = "그룹에 프로젝트를 만듭니다. 상태는 PLANNING으로 시작합니다.")
    public CustomResponse<ProjectResDTO.Created> createProject(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long groupId,
            @Valid @RequestBody ProjectReqDTO.Create request) {
        return CustomResponse.onSuccess(GeneralSuccessCode.CREATED,
                projectCommandService.createProject(userId, groupId, request));
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @PatchMapping("/projects/{projectId}")
    @Operation(summary = "프로젝트 이름·기간 수정",
            description = "보낸 필드만 수정합니다. 기간 축소 시 범위 밖 블록은 후보(POOL)로 이동되고 movedToPool로 알려줍니다.")
    public CustomResponse<ProjectResDTO.Updated> updateProject(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @Valid @RequestBody ProjectReqDTO.Update request) {
        return CustomResponse.onSuccess(projectCommandService.updateProject(userId, projectId, clientId, request));
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @DeleteMapping("/projects/{projectId}")
    @Operation(summary = "프로젝트 삭제", description = "소프트 삭제합니다. 보고 있던 멤버에게 PROJECT_DELETED op가 전파됩니다.")
    public CustomResponse<Void> deleteProject(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId) {
        projectCommandService.softDeleteProject(userId, projectId, clientId);
        return CustomResponse.onSuccess(GeneralSuccessCode.OK);
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @PatchMapping("/projects/{projectId}/status")
    @Operation(summary = "프로젝트 상태 전환", description = "PLANNING↔DONE 양방향입니다. DONE이어도 편집은 막지 않습니다.")
    public CustomResponse<ProjectResDTO.StatusChanged> changeStatus(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @Valid @RequestBody ProjectReqDTO.UpdateStatus request) {
        return CustomResponse.onSuccess(projectCommandService.changeStatus(userId, projectId, clientId, request));
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @PatchMapping("/projects/{projectId}/budget-headcount")
    @Operation(summary = "정산 인원 지정", description = "1인당 표시용 분모입니다. null을 보내면 그룹 멤버 수 연동으로 복귀합니다.")
    public CustomResponse<ProjectResDTO.HeadcountChanged> changeBudgetHeadcount(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @Valid @RequestBody ProjectReqDTO.UpdateHeadcount request) {
        return CustomResponse.onSuccess(projectCommandService.changeBudgetHeadcount(userId, projectId, clientId, request));
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @PatchMapping("/projects/{projectId}/budget")
    @Operation(summary = "총 예산 변경", description = "프로젝트 전체 목표 예산을 변경합니다. null을 보내면 예산 미설정으로 초기화됩니다.")
    public CustomResponse<ProjectResDTO.BudgetChanged> updateBudget(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @Valid @RequestBody ProjectReqDTO.UpdateBudget request
    ) {
        return CustomResponse.onSuccess(projectCommandService.updateBudget(userId, projectId, clientId, request));
    }
}
