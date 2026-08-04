import { useCallback, useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import { tokenStorage } from "../../../global/util/tokenStorage";
import { getClientId } from "../../../global/api/clientId";

/**
 * 프로젝트 op 토픽(STOMP) 구독 훅 — 실시간 협업의 수신 배관.
 *
 * 접속 계약 (백엔드 S15P11A107-123-dashboard-develop 소스 대조로 확정):
 * - 엔드포인트 /ws — raw WebSocket(SockJS 없음). dev 는 vite 프록시(ws: true)로
 *   same-origin 을 유지해 배포(nginx) 구성과 같은 모양이 된다
 * - CONNECT 프레임에 Authorization: Bearer {accessToken} — 매 (재)연결마다
 *   beforeConnect 에서 새로 읽는다: 토큰이 재발급된 뒤의 재연결이 옛 토큰으로 나가면 안 된다
 * - 하트비트 10s/10s — 서버 설정과 일치해야 죽은 연결이 정리된다
 * - 구독·발행 인가는 서버(StompAuthInterceptor)가 하고, 위반이면 연결이 끊긴다
 *
 * op 전문: { seq, type, actorId, clientId, payload }
 * - 자기 op 스킵은 서버가 하지 않는다 — 여기서 clientId 를 비교해 own 표식을 붙인다
 * - ⚠️ seq 순서 역전이 규약상 허용된다(채번 락이 커밋까지 잡지 않음) — 적용 계층은
 *   "순차 적용 + 버퍼 + 갭 fetch"여야 하며, 그건 다음 단계(op 적용)에서 이 훅의
 *   onOp 위에 얹는다. 이 훅은 수신·연결 관리까지만 책임진다.
 *
 * presence 토픽(/presence)은 같은 연결로 함께 구독한다 — 한 토픽에 두 메시지가
 * type 으로 구분되어 온다(백엔드 PresenceEventListener·DetailLockServiceImpl 대조):
 *   { type: "PRESENCE",    memberId, online }          접속/이탈
 *   { type: "DETAIL_LOCK", blockId, memberId, locked } 편집 배지
 * seq 가 없고 저널에도 안 남는 휘발 정보라 시퀀서를 태우지 않는다 — 재접속하면
 * 스냅샷의 members[].online 이 최신 상태를 준다.
 *
 * 라이브 커서(7단계)도 같은 연결을 쓴다 — SEND /app/project/{id}/cursor 로 보내면
 * 서버(RealtimeRelayController)가 actorId 만 붙여 /topic/.../cursor 로 릴레이한다.
 * DB 미저장·seq 없음. 자기 커서 스킵은 수신 측이 actorId 로 판단한다(clientId 아님).
 *
 * 보이스 시그널링(WebRTC)도 같은 연결을 쓴다 — SEND /app/project/{id}/voice/signal
 * 에 { type, targetMemberId, payload } 를 보내면 서버(RealtimeRelayController)가
 * fromMemberId·projectId 를 붙여 대상 멤버의 /user/queue/voice 로만 중계한다.
 * 개인 큐는 Spring 이 Principal 기준으로 라우팅하므로 남의 시그널을 엿볼 수 없다.
 *
 * @param {number} projectId
 * @param {{
 *   onOp?: (op: object, meta: { own: boolean }) => void,
 *   onPresence?: (msg: object) => void,
 *   onCursor?: (msg: { actorId: number, x: number, y: number, dayNo: number|null }) => void,
 *   onVoiceSignal?: (msg: { type: string, fromMemberId: number, payload: object }) => void,
 * }} [options]
 * @returns {{
 *   status: "connecting" | "connected" | "disconnected",
 *   sendCursor: (payload: { x?: number, y?: number, dayNo: number|null }) => void,
 *   sendVoiceSignal: (targetMemberId: number, type: string, payload?: object) => void,
 * }}
 */
export function useProjectOps(
  projectId,
  { onOp, onPresence, onCursor, onVoiceSignal } = {},
) {
  const isValidId = Number.isInteger(projectId);
  const [status, setStatus] = useState("connecting");
  const clientRef = useRef(null);

  // latest-ref — 콜백이 매 렌더 새 함수여도 재연결하지 않는다
  const onOpRef = useRef(onOp);
  const onPresenceRef = useRef(onPresence);
  const onCursorRef = useRef(onCursor);
  const onVoiceSignalRef = useRef(onVoiceSignal);
  useEffect(() => {
    onOpRef.current = onOp;
    onPresenceRef.current = onPresence;
    onCursorRef.current = onCursor;
    onVoiceSignalRef.current = onVoiceSignal;
  });

  /**
   * 커서 위치 발행 — 미연결(재연결 중 포함)이면 조용히 버린다. 커서는 순간
   * 정보라 큐잉할 가치가 없고, 다음 mousemove 가 곧바로 대체한다.
   */
  const sendCursor = useCallback(
    (payload) => {
      const client = clientRef.current;
      if (!client?.connected) return;
      client.publish({
        destination: `/app/project/${projectId}/cursor`,
        body: JSON.stringify(payload),
      });
    },
    [projectId],
  );

  /**
   * WebRTC 시그널 발행 — 서버가 targetMemberId 의 개인 큐로만 중계한다.
   * 미연결이면 버린다 — 유실은 보이스 훅의 주기 재시도(재-OFFER)가 흡수한다.
   */
  const sendVoiceSignal = useCallback(
    (targetMemberId, type, payload) => {
      const client = clientRef.current;
      if (!client?.connected) return;
      client.publish({
        destination: `/app/project/${projectId}/voice/signal`,
        body: JSON.stringify({ type, targetMemberId, payload }),
      });
    },
    [projectId],
  );

  useEffect(() => {
    if (!isValidId) return;

    const protocol = window.location.protocol === "https:" ? "wss" : "ws";
    const client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws`,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      beforeConnect: () => {
        client.connectHeaders = {
          Authorization: `Bearer ${tokenStorage.getAccessToken()}`,
        };
      },
      onConnect: () => {
        setStatus("connected");
        // 보이스 개인 큐를 "메인 토픽보다 먼저" 구독한다 — 내 온라인 presence 는
        // 메인 토픽 구독 순간 방송되고, 그걸 본 기존 멤버가 즉시 OFFER 를 보낸다.
        // 큐 구독이 늦으면 그 첫 OFFER 가 유실된다(Simple Broker 는 저장 안 함).
        client.subscribe(`/user/queue/voice`, (message) => {
          onVoiceSignalRef.current?.(JSON.parse(message.body));
        });
        client.subscribe(`/topic/project/${projectId}`, (message) => {
          const op = JSON.parse(message.body);
          const own = op.clientId === getClientId();
          console.debug(
            `[realtime] seq=${op.seq} ${op.type}${own ? " (own)" : ""}`,
            op.payload,
          );
          onOpRef.current?.(op, { own });
        });
        // presence 는 메인 토픽을 구독한 세션만 "보는 중"으로 세므로(서버 판정 기준)
        // 반드시 메인 구독과 같은 연결에서 함께 구독한다
        client.subscribe(`/topic/project/${projectId}/presence`, (message) => {
          const msg = JSON.parse(message.body);
          console.debug(`[realtime] presence ${msg.type}`, msg);
          onPresenceRef.current?.(msg);
        });
        // 라이브 커서 — 초당 수십 건이라 로그를 남기지 않는다
        client.subscribe(`/topic/project/${projectId}/cursor`, (message) => {
          onCursorRef.current?.(JSON.parse(message.body));
        });
      },
      onStompError: (frame) => {
        // 인증·인가 실패가 여기로 온다 (서버가 ERROR 프레임 후 연결 종료)
        console.error("[realtime] STOMP 오류:", frame.headers?.message);
        setStatus("disconnected");
      },
      onWebSocketClose: () => setStatus("disconnected"),
    });

    client.activate();
    clientRef.current = client;

    return () => {
      clientRef.current = null;
      client.deactivate();
    };
  }, [projectId, isValidId]);

  return { status, sendCursor, sendVoiceSignal };
}
