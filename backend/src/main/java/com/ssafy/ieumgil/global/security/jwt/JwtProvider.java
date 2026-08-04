package com.ssafy.ieumgil.global.security.jwt;

import com.ssafy.ieumgil.domain.auth.exception.AuthErrorCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtProvider {

    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final JwtProperties properties;

    public JwtProvider(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId) {
        return createToken(userId, TYPE_ACCESS, properties.accessTokenValidity());
    }

    public String createRefreshToken(Long userId) {
        return createToken(userId, TYPE_REFRESH, properties.refreshTokenValidity());
    }

    public Long getUserIdFromAccessToken(String token) {
        return Long.parseLong(parseTypedClaims(token, TYPE_ACCESS).getSubject());
    }

    public Long getUserIdFromRefreshToken(String token) {
        return Long.parseLong(parseTypedClaims(token, TYPE_REFRESH).getSubject());
    }

    /**
     * access 토큰의 만료 시각.
     *
     * <p>WS는 CONNECT 때 토큰을 한 번만 보고 이후 프레임에는 토큰이 실리지 않는다.
     * 이 값을 세션에 남겨 두지 않으면 한 번 연결된 세션은 만료가 존재하지 않는 채널이 된다.
     */
    public Instant getAccessTokenExpiry(String token) {
        return parseTypedClaims(token, TYPE_ACCESS).getExpiration().toInstant();
    }

    private String createToken(Long userId, String type, long validityMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + validityMillis))
                .signWith(key)
                .compact();
    }

    /** 서명·만료 검증 후 토큰 종류(access/refresh)까지 맞는지 확인한 클레임 */
    private Claims parseTypedClaims(String token, String expectedType) {
        Claims claims = parseClaims(token);
        if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new CustomException(AuthErrorCode.INVALID_TOKEN);
        }
        return claims;
    }

    private Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new CustomException(AuthErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(AuthErrorCode.INVALID_TOKEN);
        }
    }
}
