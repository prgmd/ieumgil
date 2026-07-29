package com.ssafy.ieumgil.domain.user.controller;

import com.ssafy.ieumgil.domain.user.dto.UserResDTO;
import com.ssafy.ieumgil.domain.user.service.UserCommandService;
import com.ssafy.ieumgil.domain.user.service.UserQueryService;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import com.ssafy.ieumgil.global.security.cookie.RefreshTokenCookieProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/members")
@Tag(name = "회원 Controller")
public class UserController {

    private final UserQueryService userQueryService;
    private final UserCommandService userCommandService;
    private final RefreshTokenCookieProvider refreshTokenCookieProvider;

    @GetMapping("/me")
    @Operation(summary = "내 정보 조회", description = "accessToken으로 로그인한 회원 본인의 정보를 조회합니다.")
    public CustomResponse<UserResDTO.Me> getMyInfo(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        return CustomResponse.onSuccess(userQueryService.getMyInfo(userId));
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "회원 탈퇴",
            description = "개인정보를 파기하고 소속 그룹을 정리합니다. 마지막 1인이던 그룹은 하드 삭제되며, "
                    + "refreshToken 쿠키도 만료됩니다. 같은 카카오 계정으로 재가입하면 새 회원이 됩니다.")
    public ResponseEntity<Void> withdraw(
            @Parameter(hidden = true) @AuthenticationPrincipal Long userId) {
        userCommandService.withdraw(userId);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieProvider.expire().toString())
                .build();
    }
}
