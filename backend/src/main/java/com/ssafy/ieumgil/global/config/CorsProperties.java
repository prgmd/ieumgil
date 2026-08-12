package com.ssafy.ieumgil.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * REST(SecurityConfig)와 WS(WebSocketConfig)가 공유하는 CORS origin 단일 출처.
 * 두 설정이 각자 {@code @Value}로 읽던 것을 여기로 모아 divergence를 없앤다.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(
        @DefaultValue("http://localhost:5173") List<String> allowedOrigins
) {
}
