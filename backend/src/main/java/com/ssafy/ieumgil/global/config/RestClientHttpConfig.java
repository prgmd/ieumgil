package com.ssafy.ieumgil.global.config;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.JdkClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * 일부 외부 API 게이트웨이(TourAPI 등)가 JDK HttpClient의 기본 h2c(HTTP/2 cleartext
 * upgrade) 요청을 제대로 처리하지 못해 502를 반환한다 — Spring Boot 자동 설정을 통해
 * 만들어지는 RestClient.Builder에 HTTP/1.1을 강제해 회피한다.
 * {@code ClientHttpRequestFactoryBuilder} 빈으로 등록하면 {@code RestClientBuilderConfigurer}가
 * 커스터마이저보다 먼저 적용하므로 Boot의 기본 타임아웃/redirect 설정이 유지되고,
 * {@code @RestClientTest}의 MockServerRestClientCustomizer가 이후에 실제 팩토리를
 * 덮어써서 테스트에도 영향 없다.
 * <p>주의: {@code RestClient.create()}로 직접 생성하는 클라이언트(예: KakaoOAuthClient)는
 * 이 설정을 타지 않는다 — Boot의 자동 설정 경로를 거치는 RestClient.Builder만 대상이다.
 */
@Configuration
public class RestClientHttpConfig {

    @Bean
    public JdkClientHttpRequestFactoryBuilder clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(builder -> builder.version(HttpClient.Version.HTTP_1_1));
    }
}
