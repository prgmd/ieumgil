package com.ssafy.ieumgil.domain.user.controller;

import com.ssafy.ieumgil.domain.user.dto.UserResDTO;
import com.ssafy.ieumgil.domain.user.service.UserQueryService;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v0/members")
@Tag(name = "회원 Controller")
public class UserController {

    private final UserQueryService userQueryService;

    @GetMapping("/me")
    @Operation(summary = "내 정보 조회", description = "accessToken으로 로그인한 회원 본인의 정보를 조회합니다.")
    public CustomResponse<UserResDTO.Me> getMyInfo(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return CustomResponse.onSuccess(userQueryService.getMyInfo(userId));
    }
}
