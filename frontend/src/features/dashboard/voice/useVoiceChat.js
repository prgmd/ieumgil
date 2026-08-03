import { useCallback, useEffect, useRef, useState } from "react";

// TURN 없이 공개 STUN 만 쓴다 — 같은 네트워크(데모 전제)에선 충분하다.
// 서로 다른 NAT 뒤의 두 사용자 간 연결이 안 되면 이게 원인이다(TURN 필요).
const ICE_SERVERS = [{ urls: "stun:stun.l.google.com:19302" }];

/** 이 시간 안에 연결이 수립되지 않은 피어는 걷어내고 다시 제안한다(시그널 유실 흡수) */
const RETRY_MS = 8000;

// 에코 제거·잡음 억제를 명시한다 — 스피커로 대화하면 상대 스피커 소리가 상대
// 마이크로 재유입되는데(내 목소리가 나에게 돌아옴), 이 제약이 그걸 상쇄한다.
// 그래도 다인 통화는 이어폰이 정석이다.
const AUDIO_CONSTRAINTS = {
  audio: {
    echoCancellation: true,
    noiseSuppression: true,
    autoGainControl: true,
  },
};

/**
 * 프로젝트 보이스 채팅 (풀 메시 P2P).
 *
 * "대시보드 입장 = 보이스 연결" — 별도 룸 개념이 없다. 로스터는 presence(onlineIds)
 * 를 그대로 쓴다: 온라인이 된 멤버와 연결을 만들고, 오프라인이 되면 걷는다.
 * 음성은 브라우저끼리 직결(P2P)이고 서버는 시그널(OFFER/ANSWER/ICE)만 나른다.
 *
 * 발신 규칙: **memberId 가 작은 쪽이 OFFER 를 보낸다.** 양쪽이 동시에 서로를
 * 발견해도(동시 입장) 제안이 한 방향으로만 흐르므로 글레어(offer 충돌)가 구조적으로
 * 없다. 제안·응답이 유실되면 8초 주기 청소가 미수립 피어를 걷어내 재제안을 유발한다.
 *
 * 마이크 권한이 거부되면 듣기 전용(recvonly)으로 참여한다 — "무조건 연결"을
 * 권한 거부 상황에서도 지키는 방법이다.
 *
 * ⚠️ 같은 계정의 두 탭은 지원하지 않는다 — 개인 큐가 그 계정의 모든 세션에
 * 배달되어 두 탭이 동시에 ANSWER 를 보내면 연결이 깨진다(서버 라우팅 계약).
 *
 * @param {number|undefined} myId 내 memberId (로그인 전이면 undefined — 대기)
 * @param {Set<number>} onlineIds presence 기준 접속 중 멤버 (나 포함)
 * @param {(targetMemberId, type, payload) => void} sendVoiceSignal useProjectOps 의 발행 함수
 * @param {(handler: (msg) => void) => void} registerSignalHandler 수신 콜백 등록(latest-ref 주입)
 */
export function useVoiceChat({
  myId,
  onlineIds,
  sendVoiceSignal,
  registerSignalHandler,
}) {
  const [joined, setJoined] = useState(false); // 마이크 확보(또는 듣기 전용 확정) 완료
  const [listenOnly, setListenOnly] = useState(false);
  // 입장 기본값: 마이크 꺼짐 — 자동 연결이라 본인이 모르는 새 소리가 나가면 안 된다.
  // 스피커(speakerOn)는 켜짐 — 들어오자마자 팀 대화는 들리는 게 자연스럽다.
  const [micOn, setMicOn] = useState(false);
  // 전체 음소거(출력) — 모든 상대의 소리를 끈다. 연결·수신은 유지되므로 다시 켜면
  // 즉시 들린다. 마이크와 독립 — 귀를 닫아도 내 목소리는 계속 나간다.
  const [speakerOn, setSpeakerOn] = useState(true);
  const [connectedIds, setConnectedIds] = useState(() => new Set());
  const [retryTick, setRetryTick] = useState(0); // 피어 청소 후 재제안 유발

  const localStreamRef = useRef(null);
  const peersRef = useRef(new Map()); // memberId → { pc, audioEl, pendingIce, createdAt }
  const pendingSignalsRef = useRef([]); // 마이크 권한 응답 전에 도착한 시그널 보관
  const disposedRef = useRef(false);
  const joinedRef = useRef(false);
  const micOnRef = useRef(micOn); // 스트림이 늦게 도착해도 현재 토글 상태를 적용
  const speakerOnRef = useRef(speakerOn); // 토글 후에 생기는 새 피어에도 적용

  const setPeerConnected = useCallback((memberId, connected) => {
    if (disposedRef.current) return;
    setConnectedIds((prev) => {
      if (prev.has(memberId) === connected) return prev;
      const next = new Set(prev);
      if (connected) next.add(memberId);
      else next.delete(memberId);
      return next;
    });
  }, []);

  const closePeer = useCallback(
    (memberId) => {
      const peer = peersRef.current.get(memberId);
      if (!peer) return;
      peersRef.current.delete(memberId);
      try {
        peer.pc.close();
      } catch {
        /* 이미 닫힘 */
      }
      peer.audioEl.pause();
      peer.audioEl.srcObject = null;
      setPeerConnected(memberId, false);
    },
    [setPeerConnected],
  );

  const createPeer = useCallback(
    (memberId) => {
      const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
      // DOM 에 붙이지 않는 오디오 엘리먼트 — 참조만 유지하면 재생된다.
      // 마이크 캡처 중인 페이지는 자동재생 정책의 예외라 사용자 제스처가 필요 없다.
      const audioEl = new Audio();
      audioEl.autoplay = true;
      audioEl.muted = !speakerOnRef.current; // 전체 음소거 중에 온 새 멤버도 무음
      const peer = { pc, audioEl, pendingIce: [], createdAt: Date.now() };
      peersRef.current.set(memberId, peer);

      const stream = localStreamRef.current;
      if (stream) {
        stream.getTracks().forEach((track) => pc.addTrack(track, stream));
      } else {
        pc.addTransceiver("audio", { direction: "recvonly" }); // 듣기 전용
      }

      pc.onicecandidate = (e) => {
        if (e.candidate) sendVoiceSignal(memberId, "ICE", e.candidate.toJSON());
      };
      pc.ontrack = (e) => {
        audioEl.srcObject = e.streams[0] ?? new MediaStream([e.track]);
        audioEl.play().catch(() => {});
      };
      pc.onconnectionstatechange = () => {
        setPeerConnected(memberId, pc.connectionState === "connected");
      };
      return peer;
    },
    [sendVoiceSignal, setPeerConnected],
  );

  const offerTo = useCallback(
    async (memberId) => {
      const peer = createPeer(memberId);
      try {
        const offer = await peer.pc.createOffer();
        await peer.pc.setLocalDescription(offer);
        sendVoiceSignal(memberId, "OFFER", { type: offer.type, sdp: offer.sdp });
      } catch (e) {
        console.warn("[voice] OFFER 생성 실패:", e);
        closePeer(memberId);
      }
    },
    [createPeer, sendVoiceSignal, closePeer],
  );

  // remoteDescription 설정 전에 도착한 ICE 후보를 보관했다가 한꺼번에 적용한다
  const drainIce = async (peer) => {
    while (peer.pendingIce.length > 0) {
      const candidate = peer.pendingIce.shift();
      try {
        await peer.pc.addIceCandidate(candidate);
      } catch (e) {
        console.warn("[voice] ICE 후보 적용 실패:", e);
      }
    }
  };

  const handleSignal = async (msg) => {
    if (disposedRef.current) return;
    const from = msg?.fromMemberId;
    if (from == null || from === myId) return;
    // 마이크 권한 응답을 기다리는 동안 도착한 시그널은 보관한다 — 권한 프롬프트가
    // 열려 있는 몇 초 사이에 기존 멤버의 OFFER 가 버려지면 안 된다
    if (!joinedRef.current) {
      pendingSignalsRef.current.push(msg);
      return;
    }

    const existing = peersRef.current.get(from);
    try {
      switch (msg.type) {
        case "OFFER": {
          // 상대의 재제안 — 내 쪽 잔여 연결은 버리고 응답자로 새로 만든다
          if (existing) closePeer(from);
          const peer = createPeer(from);
          await peer.pc.setRemoteDescription(msg.payload);
          await drainIce(peer);
          const answer = await peer.pc.createAnswer();
          await peer.pc.setLocalDescription(answer);
          sendVoiceSignal(from, "ANSWER", {
            type: answer.type,
            sdp: answer.sdp,
          });
          break;
        }
        case "ANSWER": {
          if (!existing || existing.pc.signalingState !== "have-local-offer")
            return; // 청소된 제안에 대한 늦은 응답 — 재제안 주기가 처리한다
          await existing.pc.setRemoteDescription(msg.payload);
          await drainIce(existing);
          break;
        }
        case "ICE": {
          if (!existing) return;
          if (existing.pc.remoteDescription)
            await existing.pc.addIceCandidate(msg.payload);
          else existing.pendingIce.push(msg.payload);
          break;
        }
        default:
          break; // 모르는 타입(향후 MUTE 등)은 조용히 무시 — 전방 호환
      }
    } catch (e) {
      console.warn(`[voice] ${msg.type} 처리 실패 (from=${from}):`, e);
    }
  };
  // latest-ref — 시그널 핸들러가 매 렌더 새 함수여도 등록은 한 번의 의미만 가진다
  const handleSignalRef = useRef(handleSignal);
  useEffect(() => {
    handleSignalRef.current = handleSignal;
    micOnRef.current = micOn;
    speakerOnRef.current = speakerOn;
  });
  useEffect(() => {
    registerSignalHandler((msg) => handleSignalRef.current(msg));
    return () => registerSignalHandler(() => {});
  }, [registerSignalHandler]);

  // ── 입장: 마이크 확보(거부 시 듣기 전용) · 퇴장: 전부 정리 ──
  useEffect(() => {
    if (myId == null) return undefined;
    disposedRef.current = false;
    // Map 객체 자체는 교체되지 않으므로(내용만 변한다) 지금 복사해도 cleanup 시점의
    // 최신 피어 목록과 같은 객체다 — lint(exhaustive-deps)의 ref 지적을 이렇게 푼다
    const peers = peersRef.current;
    // ⚠️ 취소 판정은 실행마다 갖는 지역 플래그로 한다. 공유 ref(disposedRef)로만
    // 판정하면 StrictMode 의 이중 마운트에서 두 번째 실행이 ref 를 되돌려, 첫 번째
    // getUserMedia 의 스트림까지 "유효"로 통과한다 → stop 되지 않는 유령 마이크가
    // 생기고, 그 트랙을 문 피어에게는 마이크 끄기가 영영 안 먹힌다(실측 버그).
    let cancelled = false;

    navigator.mediaDevices
      .getUserMedia(AUDIO_CONSTRAINTS)
      .then((stream) => {
        // localStreamRef 가 이미 있다면 어떤 경로든 중복 스트림이다 — 즉시 반납
        if (cancelled || disposedRef.current || localStreamRef.current) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        stream.getAudioTracks().forEach((t) => {
          t.enabled = micOnRef.current;
        });
        localStreamRef.current = stream;
        joinedRef.current = true;
        setJoined(true);
      })
      .catch(() => {
        if (cancelled || disposedRef.current) return;
        joinedRef.current = true;
        setListenOnly(true);
        setJoined(true);
      });

    return () => {
      cancelled = true;
      disposedRef.current = true;
      joinedRef.current = false;
      for (const id of [...peers.keys()]) closePeer(id);
      localStreamRef.current?.getTracks().forEach((t) => t.stop());
      localStreamRef.current = null;
    };
  }, [myId, closePeer]);

  // 권한 응답을 기다리는 동안 보관해 둔 시그널을 순서대로 처리한다
  useEffect(() => {
    if (!joined) return;
    const queued = pendingSignalsRef.current;
    pendingSignalsRef.current = [];
    queued.forEach((msg) => handleSignalRef.current(msg));
  }, [joined]);

  // ── 로스터 동기화: 온라인 = 연결 대상, 오프라인 = 정리 ──
  useEffect(() => {
    if (!joined || myId == null) return;
    onlineIds.forEach((id) => {
      if (id === myId || peersRef.current.has(id)) return;
      // 발신 규칙 — 작은 id 만 제안한다. 큰 쪽은 OFFER 수신으로 피어가 생긴다
      if (myId < id) offerTo(id);
    });
    for (const id of [...peersRef.current.keys()]) {
      if (!onlineIds.has(id)) closePeer(id);
    }
  }, [joined, onlineIds, myId, retryTick, offerTo, closePeer]);

  // 8초 주기: 수립되다 만 피어(시그널 유실·ICE 실패)를 걷어내고 재제안을 유발한다.
  // "disconnected" 는 건드리지 않는다 — ICE 가 스스로 복구할 수 있는 상태다.
  useEffect(() => {
    if (!joined) return undefined;
    const timer = setInterval(() => {
      let removed = false;
      for (const [id, peer] of [...peersRef.current.entries()]) {
        const state = peer.pc.connectionState;
        const stuck =
          (state === "new" || state === "connecting") &&
          Date.now() - peer.createdAt > RETRY_MS;
        if (stuck || state === "failed" || state === "closed") {
          closePeer(id);
          removed = true;
        }
      }
      if (removed) setRetryTick((t) => t + 1);
    }, RETRY_MS);
    return () => clearInterval(timer);
  }, [joined, closePeer]);

  // 마이크 토글 — 트랙을 끄는 것(무음 송신)이라 연결은 유지된다. 듣기 전용이면 불가
  const toggleMic = useCallback(() => {
    if (!localStreamRef.current) return;
    setMicOn((prev) => {
      const next = !prev;
      localStreamRef.current?.getAudioTracks().forEach((t) => {
        t.enabled = next;
      });
      return next;
    });
  }, []);

  // 전체 음소거 ↔ 전체 듣기 — 모든 상대 오디오의 muted 만 조작한다(즉시 전환)
  const toggleSpeaker = useCallback(() => {
    setSpeakerOn((prev) => {
      const next = !prev;
      peersRef.current.forEach((peer) => {
        peer.audioEl.muted = !next;
      });
      return next;
    });
  }, []);

  return {
    joined,
    listenOnly,
    micOn,
    toggleMic,
    speakerOn,
    toggleSpeaker,
    connectedCount: connectedIds.size,
    connectedIds,
  };
}
