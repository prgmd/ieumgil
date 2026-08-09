package com.ssafy.ieumgil.global.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 다만 그것은 <b>보내는 쪽</b>만 본다 — payload 안에 대상이 들어 있는 voice 시그널은
 * 그 대상까지 여기서 확인한다.
 * 둘 다 seq가 없고 저널에 남지 않는다 — 순간적인 정보라 재전송할 이유가 없고,
 * 초당 수십 건을 DB에 쓰면 저널이 쓰레기로 가득 찬다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class RealtimeRelayController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ProjectMembership projectMembership;

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
     *
     * <p>대상의 멤버십을 반드시 확인한다. destination 인가만 믿으면 아무 프로젝트에나
     * 들어간 사용자가 임의의 memberId로 OFFER를 쏠 수 있고, 피해자 클라이언트가 자동
     * 응답하며 마이크 트랙을 붙인다. 클라이언트를 우회한 직접 STOMP 전송이 가능하므로
     * 프론트 수정만으로는 막을 수 없고 서버가 근본이다.
     *
     * <p>커서와 달리 멤버십을 매번 조회해도 괜찮다 — 시그널은 연결 수립 때만 오가는
     * 저빈도 프레임이라, 초당 수십 건인 커서와 비용 구조가 다르다.
     */
    @MessageMapping("/project/{projectId}/voice/signal")
    public void relayVoiceSignal(@DestinationVariable Long projectId,
                                 @Payload Map<String, Object> signal,
                                 Principal principal) {
        Long targetMemberId = parseMemberId(signal.get("targetMemberId"));
        if (targetMemberId == null) {
            return;   // 대상 없는 시그널은 버린다 — 브로드캐스트하면 전원에게 오퍼가 간다
        }
        if (!projectMembership.isMember(projectId, targetMemberId)) {
            log.warn("voice 시그널 거부: from={} target={} project={} (대상이 비멤버)",
                    principal.getName(), targetMemberId, projectId);
            return;
        }

        Map<String, Object> message = new HashMap<>(signal);
        message.put("fromMemberId", Long.valueOf(principal.getName()));
        message.put("projectId", projectId);
        messagingTemplate.convertAndSendToUser(
                String.valueOf(targetMemberId), "/queue/voice", message);
    }

    /**
     * JSON 숫자는 Integer로 역직렬화되지만 클라이언트가 문자열로 보낼 수도 있어 둘 다 받는다.
     * 숫자로 읽히지 않으면 null — 멤버십을 조회할 수 없는 값이므로 시그널을 버린다.
     */
    private Long parseMemberId(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
