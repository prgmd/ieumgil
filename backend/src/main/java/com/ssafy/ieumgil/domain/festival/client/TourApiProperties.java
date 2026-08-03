package com.ssafy.ieumgil.domain.festival.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tourapi")
public record TourApiProperties(
        String serviceKey,
        String baseUrl
) {
}
