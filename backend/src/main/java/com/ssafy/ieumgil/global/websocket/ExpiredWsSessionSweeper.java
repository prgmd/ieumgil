package com.ssafy.ieumgil.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;

/**
 * 토큰이 만료된 WS 세션을 주기적으로 닫는다 (GRP-09의 남은 틈 봉합).
 *
 * <p>StompAuthInterceptor는 프레임을 <b>보내는</b> 세션만 검사한다 — 이미 성립한 구독에
 * 브로커가 밀어주는 경로는 인바운드 인터셉터를 타지 않으므로, 완전히 유휴한 뷰어는
 * 토큰 만료 후에도 op·커서·presence를 계속 수신한다. 이 스윕이 그 틈을 닫는다.
 *
 * <p>판정 기준은 CONNECT 때 세션 어트리뷰트에 남긴 만료 시각(TOKEN_EXPIRY_ATTR)이다 —
 * STOMP 세션 어트리뷰트는 전송 세션(WebSocketSession)의 어트리뷰트 맵과 같은 객체라
 * 레지스트리가 쥔 세션에서 바로 읽을 수 있다. 만료 시각이 아직 없는 세션(핸드셰이크 중,
 * CONNECT 이전)은 건너뛴다 — STOMP CONNECT 없이는 어떤 구독도 성립하지 않아 수신할
 * 데이터 자체가 없다.
 *
 * <p>REST에서 토큰을 재발급받아도 열린 WS의 만료 시각은 갱신되지 않으므로, 활성 사용자도
 * access 토큰 수명(30분)마다 한 번 끊긴다 — 인터셉터가 이미 "만료 후 첫 프레임에서 연결
 * 종료"로 동작하고 있어 새로운 정책이 아니다. 클라이언트는 자동 재연결로 새 토큰으로
 * CONNECT하고 스냅샷+afterSeq 재전송으로 복구한다.
 *
 * <p>주기 60초 — 만료 후 수신이 이어지는 최대 창이 스윕 주기만큼이다. 스케줄러가 단일
 * 스레드라 새벽 배치(축제 04:00, 유가 04:20)가 도는 동안은 스윕이 그만큼 밀리지만,
 * 그 창에서도 인터셉터의 송신 차단은 그대로 동작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredWsSessionSweeper {

    private static final long SWEEP_INTERVAL_MS = 60_000;

    private final WsSessionRegistry wsSessionRegistry;

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void closeExpiredSessions() {
        Instant now = Instant.now();
        for (WebSocketSession session : wsSessionRegistry.transportSessions()) {
            Object expiry = session.getAttributes().get(StompAuthInterceptor.TOKEN_EXPIRY_ATTR);
            if (!(expiry instanceof Instant expiresAt) || now.isBefore(expiresAt)) {
                continue;
            }
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
                log.info("WS 만료 스윕: session={} (만료 {})", session.getId(), expiresAt);
            } catch (IOException | IllegalStateException e) {
                // 이미 닫히는 중이면 목적은 달성된 것 — 나머지 세션을 계속 본다
                log.warn("WS 만료 스윕 실패: session={} ({})", session.getId(), e.getMessage());
            }
        }
    }
}
