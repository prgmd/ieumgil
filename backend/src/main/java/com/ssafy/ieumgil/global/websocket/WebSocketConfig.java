package com.ssafy.ieumgil.global.websocket;

import com.ssafy.ieumgil.global.config.CorsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * STOMP 실시간 인프라 (Step 3).
 *
 * 채널 구조 (dashboard-api.md WebSocket 절):
 * - 구독: /topic/project/{id}          — op 브로드캐스트 (+ Step 5: /presence, /cursor)
 * - 발행: /app/project/{id}/...        — cursor·voice 릴레이 (Step 5)
 * - 개인: /user/queue/...              — voice 시그널링 (Step 5)
 *
 * Simple Broker(인메모리)로 시작한다 — 단일 인스턴스 전제(v1 스코프). 다중 인스턴스로
 * 가면 브로커만 외부(RabbitMQ 등)로 교체하고 이 설정과 클라이언트는 그대로 둔다.
 *
 * SockJS는 붙이지 않았다 — 대상 브라우저(모던 크롬/사파리)가 전부 네이티브 WebSocket을
 * 지원한다. 폴백이 필요해지면 addEndpoint 체인에 .withSockJS() 한 줄이지만, 프론트
 * 클라이언트 구성이 달라지므로 켜기 전에 프론트와 합의한다.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthInterceptor stompAuthInterceptor;
    private final WsSessionRegistry sessionRegistry;
    private final CorsProperties corsProperties;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // REST의 CORS 설정과 같은 origin 목록을 쓴다 — 두 군데가 어긋나면
                // "REST는 되는데 WS만 안 되는" 종류의 디버깅 지옥이 생긴다
                .setAllowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                // 죽은 연결을 양쪽 10초 하트비트로 정리한다 — 없으면 반쯤 끊긴 세션이
                // presence에 유령으로 남는다
                .setHeartbeatValue(new long[]{10_000, 10_000})
                .setTaskScheduler(wsHeartbeatScheduler());
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // CONNECT 인증 + SUBSCRIBE/SEND 인가 — STOMP 프레임은 서블릿 필터를 타지 않으므로 필수
        registration.interceptors(stompAuthInterceptor);
    }

    /**
     * 전송 세션을 레지스트리에 넘겨 탈퇴 시 강제 종료가 가능하게 한다(GRP-09).
     *
     * <p>STOMP 계층에는 세션 id만 있고 실제 소켓이 없어서 여기서 붙잡아야 한다. 인가 캐시만
     * 비우는 방식으로는 이미 성립한 구독을 끊지 못한다 — 브로커의 푸시는 인바운드 인터셉터를
     * 타지 않기 때문이다.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessionRegistry.bindTransport(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessionRegistry.unbindTransport(session.getId());
                super.afterConnectionClosed(session, status);
            }
        });
    }

    @Bean
    public ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setPoolSize(1);
        return scheduler;
    }
}
