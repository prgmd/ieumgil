package com.ssafy.ieumgil.domain.transit.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "odsay")
public record OdsayProperties(String apiKey, String baseUrl) {
}
