package com.ssafy.ieumgil.domain.auth.controller;

import com.ssafy.ieumgil.domain.auth.dto.AuthReqDTO;
import com.ssafy.ieumgil.domain.auth.dto.AuthResDTO;
import com.ssafy.ieumgil.domain.auth.exception.AuthErrorCode;
import com.ssafy.ieumgil.domain.auth.service.AuthCommandService;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import com.ssafy.ieumgil.global.apiPayload.code.GeneralSuccessCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.global.security.cookie.RefreshTokenCookieProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/v0/auth")
@Tag(name = "인증 Controller")
public class AuthController {

    private final AuthCommandService authCommandService;
    private final RefreshTokenCookieProvider refreshTokenCookieProvider;

    @PostMapping("/login/kakao")
    @Operation(summary = "카카오 로그인", description = "인가코드로 로그인 후 accessToken은 body로, refreshToken은 httpOnly 쿠키로 발급합니다.")
    public ResponseEntity<CustomResponse<AuthResDTO.Login>> kakaoLogin(@RequestBody @Valid AuthReqDTO.KakaoLogin request) {
        AuthResDTO.LoginResult result = authCommandService.kakaoLogin(request.code());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieProvider.create(result.refreshToken()).toString())
                .body(CustomResponse.onSuccess(result.login()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "토큰 재발급", description = "httpOnly 쿠키의 리프레시 토큰으로 accessToken을 재발급합니다. (RTR 적용, 쿠키도 교체)")
    public ResponseEntity<CustomResponse<AuthResDTO.Reissue>> refresh(
            @CookieValue(value = RefreshTokenCookieProvider.COOKIE_NAME, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new CustomException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
        AuthResDTO.ReissueResult result = authCommandService.reissue(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieProvider.create(result.refreshToken()).toString())
                .body(CustomResponse.onSuccess(result.reissue()));
    }

    @PostMapping("/logout")
    @Operation(summary = "로그아웃", description = "저장된 리프레시 토큰을 삭제하고 refreshToken 쿠키를 만료시킵니다.")
    public ResponseEntity<CustomResponse<Void>> logout(@AuthenticationPrincipal Long userId) {
        authCommandService.logout(userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookieProvider.expire().toString())
                .body(CustomResponse.onSuccess(GeneralSuccessCode.OK));
    }
}
