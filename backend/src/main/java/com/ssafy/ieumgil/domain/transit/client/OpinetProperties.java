package com.ssafy.ieumgil.domain.transit.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opinet")
public record OpinetProperties(
        String apiKey,
        String baseUrl
) {
}
