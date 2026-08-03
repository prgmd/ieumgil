package com.ssafy.ieumgil.global.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * voice 시그널 릴레이의 대상 검증 (VOC-01 보안).
 *
 * <p>destination 인가(StompAuthInterceptor)는 <b>보내는 쪽</b>만 본다. payload의
 * targetMemberId를 서버가 확인하지 않으면, 아무 프로젝트에나 들어간 사용자가 임의의
 * memberId로 OFFER를 쏘고 피해자 클라이언트가 자동 응답하며 마이크를 여는 경로가 열린다.
 */
@ExtendWith(MockitoExtension.class)
class RealtimeRelayControllerTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long SENDER_ID = 10L;
    private static final Long TARGET_ID = 20L;

    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private ProjectMembership projectMembership;

    @InjectMocks
    private RealtimeRelayController controller;

    private static Map<String, Object> signalTo(Object targetMemberId) {
        Map<String, Object> signal = new HashMap<>();
        signal.put("type", "OFFER");
        signal.put("sdp", "v=0...");
        if (targetMemberId != null) {
            signal.put("targetMemberId", targetMemberId);
        }
        return signal;
    }

    @Test
    @DisplayName("대상이 프로젝트 멤버면 개인 큐로 중계하고 발신자/프로젝트를 덧붙인다")
    void relaysToMemberTarget() {
        given(projectMembership.isMember(PROJECT_ID, TARGET_ID)).willReturn(true);

        controller.relayVoiceSignal(PROJECT_ID, signalTo(TARGET_ID), new StompPrincipal(SENDER_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSendToUser(eq("20"), eq("/queue/voice"), sent.capture());
        assertThat(sent.getValue())
                .containsEntry("fromMemberId", SENDER_ID)
                .containsEntry("projectId", PROJECT_ID)
                .containsEntry("type", "OFFER");
    }

    @Test
    @DisplayName("대상이 비멤버면 중계하지 않는다 — 마이크 도청 경로 차단")
    void dropsSignalToNonMember() {
        given(projectMembership.isMember(PROJECT_ID, TARGET_ID)).willReturn(false);

        controller.relayVoiceSignal(PROJECT_ID, signalTo(TARGET_ID), new StompPrincipal(SENDER_ID));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("targetMemberId가 없으면 버린다 — 브로드캐스트하면 전원에게 오퍼가 간다")
    void dropsSignalWithoutTarget() {
        controller.relayVoiceSignal(PROJECT_ID, signalTo(null), new StompPrincipal(SENDER_ID));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        verifyNoInteractions(projectMembership);
    }

    @Test
    @DisplayName("targetMemberId가 숫자가 아니면 멤버십을 조회하지 않고 버린다")
    void dropsSignalWithNonNumericTarget() {
        controller.relayVoiceSignal(PROJECT_ID, signalTo("../admin"), new StompPrincipal(SENDER_ID));

        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
        verifyNoInteractions(projectMembership);
    }

    @Test
    @DisplayName("문자열로 온 targetMemberId도 같은 대상으로 취급한다 — 검증 우회 방지")
    void treatsStringTargetAsSameMember() {
        given(projectMembership.isMember(PROJECT_ID, TARGET_ID)).willReturn(false);

        controller.relayVoiceSignal(PROJECT_ID, signalTo("20"), new StompPrincipal(SENDER_ID));

        verify(projectMembership).isMember(PROJECT_ID, TARGET_ID);
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("커서는 멤버십을 조회하지 않는다 — 초당 수십 건이라 DB를 칠 수 없다")
    void cursorRelayDoesNotQueryMembership() {
        controller.relayCursor(PROJECT_ID, new HashMap<>(Map.of("x", 0.5, "y", 0.5)),
                new StompPrincipal(SENDER_ID));

        verify(messagingTemplate).convertAndSend(eq("/topic/project/1/cursor"), any(Object.class));
        verifyNoInteractions(projectMembership);
    }
}
