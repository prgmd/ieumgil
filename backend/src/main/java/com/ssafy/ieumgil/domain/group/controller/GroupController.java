package com.ssafy.ieumgil.domain.group.controller;

import com.ssafy.ieumgil.domain.group.annotation.GroupMember;
import com.ssafy.ieumgil.domain.group.dto.GroupReqDTO;
import com.ssafy.ieumgil.domain.group.dto.GroupResDTO;
import com.ssafy.ieumgil.domain.group.service.GroupCommandService;
import com.ssafy.ieumgil.domain.group.service.GroupQueryService;
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

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/groups")
@Tag(name = "그룹 Controller")
public class GroupController {

    private final GroupCommandService groupCommandService;
    private final GroupQueryService groupQueryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "그룹 생성", description = "그룹을 만들고 생성자를 첫 멤버로 등록한 뒤 초대 코드를 발급합니다.")
    public CustomResponse<GroupResDTO.Created> createGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Valid @RequestBody GroupReqDTO.Create request) {
        return CustomResponse.onSuccess(GeneralSuccessCode.CREATED, groupCommandService.createGroup(userId, request));
    }

    @GetMapping
    @Operation(summary = "내 그룹 목록 조회", description = "내가 소속된 그룹의 멤버 수·프로젝트 수·멤버 아바타를 함께 조회합니다.")
    public CustomResponse<List<GroupResDTO.Summary>> getMyGroups(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return CustomResponse.onSuccess(groupQueryService.getMyGroups(userId));
    }

    @PostMapping("/join")
    @Operation(summary = "초대 코드로 그룹 입장", description = "초대 코드가 유효하고 정원(10명)이 남아 있으면 그룹에 참여합니다.")
    public CustomResponse<GroupResDTO.Joined> joinGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @Valid @RequestBody GroupReqDTO.Join request) {
        return CustomResponse.onSuccess(groupCommandService.joinGroup(userId, request));
    }

    @GroupMember
    @GetMapping("/{groupId}/members")
    @Operation(summary = "멤버 목록 조회", description = "그룹의 멤버 목록과 초대 코드를 함께 조회합니다. online은 presence 연동 전까지 false입니다.")
    public CustomResponse<GroupResDTO.MemberList> getMembers(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long groupId) {
        return CustomResponse.onSuccess(groupQueryService.getMembers(userId, groupId));
    }

    @GroupMember
    @PostMapping("/{groupId}/invite-code")
    @Operation(summary = "초대 코드 재발급", description = "새 코드를 발급하고 기존 코드를 즉시 무효화합니다. 모든 멤버가 가능합니다(flat 모델).")
    public CustomResponse<GroupResDTO.InviteCode> reissueInviteCode(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long groupId) {
        return CustomResponse.onSuccess(groupCommandService.reissueInviteCode(userId, groupId));
    }

    @GroupMember
    @DeleteMapping("/{groupId}/members/me")
    @Operation(summary = "그룹 나가기", description = "본인만 나갈 수 있습니다. 마지막 1인이 나가면 그룹이 하드 삭제됩니다.")
    public CustomResponse<GroupResDTO.Left> leaveGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long groupId) {
        return CustomResponse.onSuccess(groupCommandService.leaveGroup(userId, groupId));
    }

    @GroupMember
    @PatchMapping("/{groupId}")
    @Operation(summary = "그룹명 수정", description = "그룹의 모든 멤버가 수정할 수 있습니다(flat 모델).")
    public CustomResponse<GroupResDTO.Updated> updateGroupName(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupReqDTO.UpdateName request) {
        return CustomResponse.onSuccess(groupCommandService.updateGroupName(userId, groupId, request));
    }

    @GroupMember
    @DeleteMapping("/{groupId}")
    @Operation(summary = "그룹 삭제", description = "소프트 삭제합니다. 오조작 방지를 위해 그룹명을 다시 입력받아 검증합니다.")
    public CustomResponse<Void> deleteGroup(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long groupId,
            @Valid @RequestBody GroupReqDTO.Delete request) {
        groupCommandService.softDeleteGroup(userId, groupId, request);
        return CustomResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
