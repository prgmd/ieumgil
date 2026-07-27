package com.ssafy.ieumgil.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public class AuthReqDTO {

    @Schema(description = "카카오 로그인 요청")
    public record KakaoLogin(
            @Schema(description = "카카오 인가코드")
            @NotBlank(message = "인가코드는 필수입니다.")
            String code
    ) {
    }
}
