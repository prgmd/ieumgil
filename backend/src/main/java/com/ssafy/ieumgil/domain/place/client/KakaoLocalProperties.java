package com.ssafy.ieumgil.domain.place.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao.local")
public record KakaoLocalProperties(
        String restApiKey,
        String baseUrl
) {
}
