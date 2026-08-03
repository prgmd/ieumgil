package com.ssafy.ieumgil.global.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

/**
 * DB를 거치지 않는 순수 릴레이 (Step 5) — cursor·voice.
 *
 * 인가는 StompAuthInterceptor가 SEND 프레임에서 이미 끝냈다(/app/project/{id}/** 패턴).
 * 둘 다 seq가 없고 저널에 남지 않는다 — 순간적인 정보라 재전송할 이유가 없고,
 * 초당 수십 건을 DB에 쓰면 저널이 쓰레기로 가득 찬다.
 */
@Controller
@RequiredArgsConstructor
public class RealtimeRelayController {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 라이브 커서 (CUR-01): {x, y, dayNo} 그대로 릴레이 + actorId만 붙인다.
     * 요청자 본인 스킵은 수신 측이 actorId로 판단한다 (자기 커서는 자기가 그린다).
     */
    @MessageMapping("/project/{projectId}/cursor")
    public void relayCursor(@DestinationVariable Long projectId,
                            @Payload Map<String, Object> cursor,
                            Principal principal) {
        Map<String, Object> message = new HashMap<>(cursor);
        message.put("actorId", Long.valueOf(principal.getName()));
        messagingTemplate.convertAndSend("/topic/project/" + projectId + "/cursor", message);
    }

    /**
     * voice 시그널링 (VOC-01): WebRTC OFFER/ANSWER/ICE를 대상 멤버의 개인 큐로 중계한다.
     * 미디어는 P2P — 서버는 연결 성립에 필요한 시그널만 나른다.
     * 수신 destination: /user/queue/voice (Principal name = memberId 문자열)
     */
    @MessageMapping("/project/{projectId}/voice/signal")
    public void relayVoiceSignal(@DestinationVariable Long projectId,
                                 @Payload Map<String, Object> signal,
                                 Principal principal) {
        Object target = signal.get("targetMemberId");
        if (target == null) {
            return;   // 대상 없는 시그널은 버린다 — 브로드캐스트하면 전원에게 오퍼가 간다
        }
        Map<String, Object> message = new HashMap<>(signal);
        message.put("fromMemberId", Long.valueOf(principal.getName()));
        message.put("projectId", projectId);
        messagingTemplate.convertAndSendToUser(String.valueOf(target), "/queue/voice", message);
    }
}
