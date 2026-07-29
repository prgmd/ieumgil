package com.ssafy.ieumgil.domain.project.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 프로젝트는 경로가 두 갈래다 —
 * 목록·생성은 그룹 하위(/groups/{groupId}/projects), 수정·삭제는 단독(/projects/{projectId}).
 * 그래서 공통 접두사를 /api/v0까지만 두고 메서드에서 나머지를 적는다.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "프로젝트 Controller")
public class ProjectController {

    private final ProjectCommandService projectCommandService;
    private final ProjectQueryService projectQueryService;

    @GroupMember
    @GetMapping("/groups/{groupId}/projects")
    @Operation(summary = "프로젝트 카드 목록 조회", description = "그룹의 프로젝트를 최근 생성 순으로 조회합니다.")
    public CustomResponse<List<ProjectResDTO.Card>> getGroupProjects(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long groupId) {
        return CustomResponse.onSuccess(projectQueryService.getGroupProjects(userId, groupId));
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
    @Operation(summary = "프로젝트 이름·기간 수정", description = "보낸 필드만 수정합니다. movedToPool은 블록 기능 구현 전까지 빈 배열입니다.")
    public CustomResponse<ProjectResDTO.Updated> updateProject(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @Valid @RequestBody ProjectReqDTO.Update request) {
        return CustomResponse.onSuccess(projectCommandService.updateProject(userId, projectId, request));
    }

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @DeleteMapping("/projects/{projectId}")
    @Operation(summary = "프로젝트 삭제", description = "소프트 삭제합니다. 모든 멤버가 가능합니다(flat 모델).")
    public CustomResponse<Void> deleteProject(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId) {
        projectCommandService.softDeleteProject(userId, projectId);
        return CustomResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
