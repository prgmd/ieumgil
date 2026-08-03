package com.ssafy.ieumgil.domain.transit.controller;

import com.ssafy.ieumgil.domain.group.annotation.GroupMember;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateReqDTO;
import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.service.TransitCandidateService;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 교통 후보 계산.
 *
 * <p>기존 {@code /api/transit}과 컨트롤러를 나눈 이유는 이 기능이 프로젝트 스코프이기
 * 때문이다 — 프로젝트 멤버 인가가 필수라 경로 자체가 다르다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "교통 후보 Controller", description = "교통 후보 API")
public class TransitCandidateController {

    private final TransitCandidateService transitCandidateService;

    @GroupMember(GroupMember.Source.PROJECT_ID)
    @PostMapping("/transit-candidates")
    @Operation(summary = "교통 후보 일괄 계산",
            description = "체인 순서의 블록 id를 받아 연속 구간마다 이동수단 후보를 계산합니다. 블록은 생성하지 않습니다.")
    public CustomResponse<TransitCandidateResDTO.Result> calculate(
            @PathVariable Long projectId,
            @Valid @RequestBody TransitCandidateReqDTO.Calculate request) {
        return CustomResponse.onSuccess(
                transitCandidateService.calculate(projectId, request.blockIds()));
    }
}
