package com.ssafy.ieumgil.global.security.cookie;

import com.ssafy.ieumgil.global.security.jwt.JwtProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * refreshToken httpOnly 쿠키 생성/만료 담당.
 * - 프론트는 refreshToken을 JS로 다루지 않고, withCredentials 요청 시 브라우저가 쿠키를 자동 전송한다.
 * - 쿠키 path를 auth 하위로 제한해 다른 API 요청에는 실려가지 않도록 한다.
 */
@Component
public class RefreshTokenCookieProvider {

    public static final String COOKIE_NAME = "refreshToken";

    private static final String COOKIE_PATH = "/api/v0/auth";

    private final JwtProperties jwtProperties;
    private final boolean secure;
    private final String sameSite;

    public RefreshTokenCookieProvider(
            JwtProperties jwtProperties,
            @Value("${auth.refresh-cookie.secure:false}") boolean secure,
            @Value("${auth.refresh-cookie.same-site:Lax}") String sameSite) {
        this.jwtProperties = jwtProperties;
        this.secure = secure;
        this.sameSite = sameSite;
    }

    public ResponseCookie create(String refreshToken) {
        return ResponseCookie.from(COOKIE_NAME, refreshToken)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(COOKIE_PATH)
                .maxAge(Duration.ofMillis(jwtProperties.refreshTokenValidity()))
                .build();
    }

    public ResponseCookie expire() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
    }
}
