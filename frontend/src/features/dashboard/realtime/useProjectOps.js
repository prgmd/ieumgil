import { useEffect, useRef, useState } from "react";
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
 * @param {number} projectId
 * @param {{ onOp?: (op: object, meta: { own: boolean }) => void }} [options]
 * @returns {{ status: "connecting" | "connected" | "disconnected" }}
 */
export function useProjectOps(projectId, { onOp } = {}) {
  const isValidId = Number.isInteger(projectId);
  const [status, setStatus] = useState("connecting");

  // latest-ref — onOp 이 매 렌더 새 함수여도 재연결하지 않는다
  const onOpRef = useRef(onOp);
  useEffect(() => {
    onOpRef.current = onOp;
  });

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
        client.subscribe(`/topic/project/${projectId}`, (message) => {
          const op = JSON.parse(message.body);
          const own = op.clientId === getClientId();
          console.debug(
            `[realtime] seq=${op.seq} ${op.type}${own ? " (own)" : ""}`,
            op.payload,
          );
          onOpRef.current?.(op, { own });
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

    return () => {
      client.deactivate();
    };
  }, [projectId, isValidId]);

  return { status };
}
