package com.ssafy.ieumgil.domain.block.controller;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.block.service.BlockCommandService;
import com.ssafy.ieumgil.domain.block.service.DetailLockService;
import com.ssafy.ieumgil.domain.group.annotation.GroupMember;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블록도 프로젝트처럼 경로가 두 갈래다 —
 * 생성은 프로젝트 하위(/projects/{projectId}/blocks), 나머지는 단독(/blocks/{blockId}).
 *
 * X-Client-Id는 브로드캐스트에서 "요청자 본인 스킵"에 쓰인다. 헤더가 없어도 동작은 하지만
 * 그 클라이언트는 자기 변경을 되돌려받게 되므로, 프론트 axiosInstance가 항상 실어 보낸다.
 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api")
@Tag(name = "블록 Controller")
public class BlockController {

    private static final String CLIENT_ID_HEADER = "X-Client-Id";

    private final BlockCommandService blockCommandService;
    private final DetailLockService detailLockService;

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @PostMapping("/projects/{projectId}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "블록 생성",
            description = "지도/직접/교통 블록을 생성합니다. startOffsetMinutes(Day 1 00:00 기준 경과 분)가 null이면 후보(POOL)로 생성됩니다.")
    public CustomResponse<BlockResDTO.Created> createBlock(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long projectId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @Valid @RequestBody BlockReqDTO.Create request) {
        return CustomResponse.onSuccess(GeneralSuccessCode.CREATED,
                blockCommandService.createBlock(userId, projectId, clientId, request));
    }

    @GroupMember(GroupMember.Source.BLOCK_ID)
    @PatchMapping("/blocks/{blockId}/fields")
    @Operation(summary = "필드 단위 LWW 배치 갱신",
            description = "여러 필드를 한 번에 저장합니다. 필드별 독립 판정 — 스테일 필드는 applied:false로 무시됩니다.")
    public CustomResponse<BlockResDTO.FieldsApplied> updateFields(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long blockId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @Valid @RequestBody BlockReqDTO.UpdateFields request) {
        return CustomResponse.onSuccess(blockCommandService.updateFields(userId, blockId, clientId, request));
    }

    @GroupMember(GroupMember.Source.BLOCK_ID)
    @PatchMapping("/blocks/{blockId}/position")
    @Operation(summary = "블록 이동",
            description = "체인 재정렬/후보↔체인/Day 이동. 목적지는 startOffsetMinutes로 지정하며 옮긴 블록 1행만 갱신됩니다.")
    public CustomResponse<BlockResDTO.Moved> move(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long blockId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId,
            @Valid @RequestBody BlockReqDTO.Move request) {
        return CustomResponse.onSuccess(blockCommandService.move(userId, blockId, clientId, request));
    }

    @GroupMember(GroupMember.Source.BLOCK_ID)
    @DeleteMapping("/blocks/{blockId}")
    @Operation(summary = "블록 삭제", description = "소프트 삭제(tombstone)합니다. 삭제 후 도착한 지연 op는 410으로 응답됩니다.")
    public CustomResponse<Void> deleteBlock(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long blockId,
            @RequestHeader(value = CLIENT_ID_HEADER, required = false) String clientId) {
        blockCommandService.softDelete(userId, blockId, clientId);
        return CustomResponse.onSuccess(GeneralSuccessCode.OK);
    }

    // ----- detail-lock (BLK-08) — 세부 내용 텍스트 편집 락 -----

    @GroupMember(GroupMember.Source.BLOCK_ID)
    @PostMapping("/blocks/{blockId}/detail-lock")
    @Operation(summary = "편집 락 획득", description = "Redis SET NX, TTL 30초. 실패 시 acquired:false와 현재 holder·잔여 TTL을 반환합니다.")
    public CustomResponse<BlockResDTO.LockResult> acquireLock(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long blockId) {
        return CustomResponse.onSuccess(detailLockService.acquire(userId, blockId));
    }

    @GroupMember(GroupMember.Source.BLOCK_ID)
    @PutMapping("/blocks/{blockId}/detail-lock")
    @Operation(summary = "락 하트비트", description = "TTL을 30초로 연장합니다(클라이언트 10초 주기). 소유자가 아니면 409입니다.")
    public CustomResponse<BlockResDTO.LockHeartbeat> heartbeatLock(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long blockId) {
        return CustomResponse.onSuccess(detailLockService.heartbeat(userId, blockId));
    }

    @GroupMember(GroupMember.Source.BLOCK_ID)
    @DeleteMapping("/blocks/{blockId}/detail-lock")
    @Operation(summary = "락 해제", description = "소유자의 해제만 유효하며 멱등입니다(만료 후 해제 요청도 200).")
    public CustomResponse<Void> releaseLock(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId,
            @PathVariable Long blockId) {
        detailLockService.release(userId, blockId);
        return CustomResponse.onSuccess(GeneralSuccessCode.OK);
    }
}
