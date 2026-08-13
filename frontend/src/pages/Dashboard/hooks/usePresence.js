// pages/Dashboard/hooks/usePresence.js
//
// 실시간 "함께 있는 느낌"(6·7단계) — 접속 멤버·편집 락·최근 수정자·라이브 커서·
// Day 뷰어를 한곳에 모은다. 커서/presence 수신은 index.jsx 가 소유한 useProjectOps
// 로 들어오므로, 이 훅은 적용 함수(applyPresenceMessage/applyCursorMessage)를
// 반환하고 index.jsx 가 그것을 ref 에 꽂아 파이프라인에 물린다(순환 회피). 반대로
// 송신 채널 sendCursor 는 이 훅의 입력으로 흘러 들어온다.
//
// setBoardMembers/setOnlineIds/setLastEditors 를 밖으로 노출하는 이유: 스냅샷 시드와
// 원격 op 스위치(MEMBER_JOINED/LEFT·BLOCK_*)가 이 상태들을 갱신해야 하는데, 그
// 오케스트레이션은 index.jsx 가 소유한다.

import { useState, useRef, useEffect, useCallback } from "react";
import { dayNoOf, isServerBlock, PX, TL_PAD_TOP } from "../dashboardHelpers";
import * as blockApi from "../../../features/dashboard/api/dashboardApi";

export function usePresence({
  activeDay,
  timelineDOMRef,
  sendCursor,
  currentUser,
  items,
  editingBlockId,
  timelineStart,
  TL_PAD_LEFT,
}) {
  // 커서 위치 수신은 대시보드 상태를 거치지 않고 RemoteCursorLayer(타임라인·페이지
  // 두 장)로 직행한다 — 초당 수십 건이 보드 전체를 리렌더하지 않도록 성능을 격리.
  // 어느 레이어 소관인지는 각 레이어가 메시지의 area 로 스스로 판단한다.
  const tlCursorHandlerRef = useRef(() => {});
  const pageCursorHandlerRef = useRef(() => {});
  const registerTlCursorHandler = useCallback((fn) => {
    tlCursorHandlerRef.current = fn;
  }, []);
  const registerPageCursorHandler = useCallback((fn) => {
    pageCursorHandlerRef.current = fn;
  }, []);

  // ── 함께 있는 느낌 (6단계) ───────────────────────────
  // members: 스냅샷이 시드하고 MEMBER_JOINED/LEFT op 가 갱신한다 (상단바 아바타).
  // onlineIds: 시드는 members[].online, 이후는 PRESENCE 메시지 — 초록 점의 진실.
  // detailLocks: blockId → 락 소유 memberId. DETAIL_LOCK 메시지로만 갱신되는 휘발
  //   정보라 새로고침하면 기존 락은 다음 획득/해제 전까지 안 보인다(advisory 라 감수).
  const [boardMembers, setBoardMembers] = useState([]);
  const [onlineIds, setOnlineIds] = useState(() => new Set());
  const [detailLocks, setDetailLocks] = useState({});
  // "누가 어느 Day 를 보는 중인가" (Day 탭 점 표시, 7단계) — 커서 메시지의 dayNo 로
  // 유지된다. Day 가 바뀔 때만 setState 하므로 초당 수십 건의 커서 트래픽이 보드
  // 리렌더로 이어지지 않는다 — 커서 위치는 레이어 소관, 여기는 Day 만.
  const [viewingDays, setViewingDays] = useState({}); // actorId → dayNo
  const cursorLastSeenRef = useRef({}); // actorId → ts (하트비트 만료 판정)
  // 블록별 "가장 최근 수정자"의 세션 오버레이 — 블록 op(생성·필드수정·이동)의
  // actorId 로 기록한다. 영속 값은 서버의 block.lastEditedById 이고(PRS-04), 이 맵은
  // op 가 도착한 순간 재조회 없이 배지를 갱신하려는 용도 + 아직 서버 id 를 못 받은
  // 낙관적 생성 블록을 덮는 용도다. 시드마다 비운다 — 스냅샷이 더 최신이다
  // (우선순위는 editorBadgeOf 참조).
  const [lastEditors, setLastEditors] = useState({}); // blockId → memberId

  const recordBlockEditor = (blockId, memberId) => {
    if (blockId == null || memberId == null) return;
    setLastEditors((prev) =>
      prev[blockId] === memberId ? prev : { ...prev, [blockId]: memberId },
    );
  };

  const applyPresenceMessage = (msg) => {
    if (msg?.type === "PRESENCE") {
      setOnlineIds((prev) => {
        if (prev.has(msg.memberId) === !!msg.online) return prev; // 변화 없음
        const next = new Set(prev);
        if (msg.online) next.add(msg.memberId);
        else next.delete(msg.memberId);
        return next;
      });
      // 락 만료(TTL)는 브로드캐스트가 없다 — 편집자가 해제 없이 사라지면(브라우저
      // 강제 종료 등) 배지가 남는다. 이탈 신호를 만료의 근사로 삼아 그의 배지를
      // 걷는다. 실제 락도 하트비트가 끊겨 30초 내 만료된다.
      if (!msg.online) {
        setDetailLocks((prev) => {
          const entries = Object.entries(prev).filter(
            ([, holder]) => holder !== msg.memberId,
          );
          return entries.length === Object.keys(prev).length
            ? prev
            : Object.fromEntries(entries);
        });
        // Day 탭의 "보는 중" 점도 함께 걷는다 — 하트비트 만료(12초)보다 빠르다
        setViewingDays((prev) => {
          if (!(msg.memberId in prev)) return prev;
          const next = { ...prev };
          delete next[msg.memberId];
          return next;
        });
      }
    } else if (msg?.type === "DETAIL_LOCK") {
      setDetailLocks((prev) => {
        if (msg.locked) return { ...prev, [msg.blockId]: msg.memberId };
        // 해제는 소유자 것만 지운다 — 늦게 도착한 옛 소유자의 해제가
        // 새 소유자의 배지를 지우면 안 된다
        if (prev[msg.blockId] !== msg.memberId) return prev;
        const next = { ...prev };
        delete next[msg.blockId];
        return next;
      });
    }
  };

  // ── 커서 메시지 라우팅 — 위치는 두 레이어로, dayNo 는 viewingDays 로 ──
  // 모든 커서 메시지에 발신자가 보는 dayNo 가 실려 온다(마우스가 멈춰 있어도
  // 5초 주기 view 하트비트가 유지).
  const applyCursorMessage = (msg) => {
    if (msg?.actorId == null) return;
    tlCursorHandlerRef.current(msg);
    pageCursorHandlerRef.current(msg);
    // 내 Day 는 표시하지 않는다 — 내가 보는 탭이 곧 내 위치다
    if (msg.actorId === currentUser?.id) return;
    if (msg.dayNo == null) return;
    cursorLastSeenRef.current[msg.actorId] = Date.now();
    setViewingDays((prev) =>
      prev[msg.actorId] === msg.dayNo
        ? prev
        : { ...prev, [msg.actorId]: msg.dayNo },
    );
  };

  // 하트비트(5초)가 두 번 유실되면 떠난 것으로 본다 — 잔점 방지
  useEffect(() => {
    const timer = setInterval(() => {
      const now = Date.now();
      setViewingDays((prev) => {
        const alive = Object.keys(prev).filter(
          (id) => now - (cursorLastSeenRef.current[id] ?? 0) < 12_000,
        );
        return alive.length === Object.keys(prev).length
          ? prev
          : Object.fromEntries(alive.map((id) => [id, prev[id]]));
      });
    }, 5000);
    return () => clearInterval(timer);
  }, []);

  // 내가 보는 Day 를 알린다 — Day 전환 즉시 + 5초 주기(마우스가 안 움직여도 유지)
  useEffect(() => {
    const dayNo = dayNoOf(activeDay);
    sendCursor({ area: "view", dayNo });
    const timer = setInterval(() => sendCursor({ area: "view", dayNo }), 5000);
    return () => clearInterval(timer);
  }, [activeDay, sendCursor]);

  // ── 편집 락 수명 = 편집 모달 수명 (6단계) ──
  // 모달을 열면 획득 → 10초 주기 하트비트(TTL 30초) → 닫으면 해제.
  // 남이 잡고 있으면 비고(detail) 입력이 잠긴다(BlockEditForm 의 detailLocked).
  // 이때 4초 주기로 재획득을 시도한다 — 소유자가 놓으면(모달 닫기·크래시 TTL 만료)
  // 재오픈 없이 내가 이어받아 편집할 수 있다.
  useEffect(() => {
    if (!editingBlockId || !isServerBlock(editingBlockId)) return undefined;
    const blockId = editingBlockId;
    let heartbeatTimer = null;
    let retryTimer = null;
    let acquired = false;
    let cancelled = false;

    const promote = () => {
      acquired = true;
      if (retryTimer) {
        clearInterval(retryTimer);
        retryTimer = null;
      }
      // 내가 잡았으니 남의 배지 흔적을 걷는다(재획득이 TTL 만료로 성사된 경우
      // 해제 메시지가 없어 옛 소유자 배지가 남아 있을 수 있다)
      setDetailLocks((prev) => {
        if (!(blockId in prev)) return prev;
        const next = { ...prev };
        delete next[blockId];
        return next;
      });
      heartbeatTimer = setInterval(() => {
        blockApi.heartbeatDetailLock(blockId).catch(() => {});
      }, 10_000);
    };

    const attempt = () =>
      blockApi
        .acquireDetailLock(blockId)
        .then((r) => {
          if (cancelled) {
            // 응답 전에 모달이 닫혔다 — 방금 얻은 락을 바로 되돌려 준다
            if (r?.acquired) blockApi.releaseDetailLock(blockId).catch(() => {});
            return;
          }
          // 이미 락을 쥐었다 — 겹치거나 역순으로 도착한 재시도 응답이 promote 를
          // 두 번 태워 heartbeat 를 누수하거나, stale holder 로 배지를 되세우지 않게 한다
          if (acquired) return;
          if (r?.acquired) {
            promote();
          } else if (r?.holder != null) {
            // 남이 잡고 있다 — 배지에 반영하고(구독 이전부터 있던 락이면 이 경로가
            // 유일한 단서다) 놓일 때까지 재획득을 재시도한다
            setDetailLocks((prev) => ({ ...prev, [blockId]: r.holder }));
            if (!retryTimer) {
              retryTimer = setInterval(() => {
                attempt().catch(() => {});
              }, 4_000);
            }
          }
        });

    attempt().catch(() => {});

    return () => {
      cancelled = true;
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (retryTimer) clearInterval(retryTimer);
      if (acquired) blockApi.releaseDetailLock(blockId).catch(() => {});
    };
  }, [editingBlockId]);

  const pageDOMRef = useRef(null); // 페이지 좌표 커서(area:"page")의 기준 박스

  // ── 라이브 커서 송신 (7단계) — 명세의 50ms 스로틀, 대시보드 전역 ──
  // 타임라인 위에서는 "가로 비율 + 절대 분 오프셋"(area:"tl") — 상대와 내 스크롤·시작
  // 시각이 달라도 같은 시간 위치에 그려진다(축 기준선 timelineStart 를 더해 절대 분으로 만든다). 그 밖(후보·사이드 등)에서는 페이지
  // 비율 좌표(area:"page") — 창 크기가 달라도 대략 같은 자리를 가리킨다.
  const lastCursorSendRef = useRef(0);
  const handlePageCursorMove = (e) => {
    const now = Date.now();
    if (now - lastCursorSendRef.current < 50) return;
    lastCursorSendRef.current = now;
    const dayNo = dayNoOf(activeDay);

    const tlEl = timelineDOMRef.current;
    const tlRect = tlEl?.getBoundingClientRect();
    const inTimeline =
      !!tlRect &&
      e.clientX >= tlRect.left &&
      e.clientX <= tlRect.right &&
      e.clientY >= tlRect.top &&
      e.clientY <= tlRect.bottom;

    if (inTimeline) {
      sendCursor({
        area: "tl",
        x: (e.clientX - tlRect.left - TL_PAD_LEFT) / (tlRect.width - TL_PAD_LEFT),
        y:
          timelineStart +
          (e.clientY - tlRect.top + tlEl.scrollTop - TL_PAD_TOP) / PX,
        dayNo,
      });
      return;
    }

    const pageRect = pageDOMRef.current?.getBoundingClientRect();
    if (!pageRect) return;
    sendCursor({
      area: "page",
      x: (e.clientX - pageRect.left) / pageRect.width,
      y: (e.clientY - pageRect.top) / pageRect.height,
      dayNo,
    });
  };

  // 편집 배지에 쓸 이름 — 락 소유자가 멤버 목록에 없으면(탈퇴 직후 등) 뭉뚱그린다
  const nicknameOf = (memberId) =>
    boardMembers.find((m) => m.memberId === memberId)?.nickname ?? "다른 멤버";
  // 남이 잡은 락만 배지가 된다 — 내 락(내 다른 탭 포함, memberId 기준)은 표시하지 않는다
  const lockBadgeOf = (blockId) => {
    const holder = detailLocks[blockId];
    return holder != null && holder !== currentUser?.id
      ? nicknameOf(holder)
      : null;
  };

  // 블록 좌상단의 "가장 최근 수정자" 아바타. 우선순위는
  //   ① 시드 이후 도착한 op(lastEditors) → ② 서버 영속값(lastEditedById, PRS-04)
  //   → ③ 작성자(authorId, 005 마이그레이션 이전 행의 폴백)
  // ①이 ②보다 앞서는 건 op 가 스냅샷보다 뒤의 사실이기 때문이다(시드 때 ①은 비워진다).
  // 멤버 정보가 없으면(탈퇴 등) 감춘다.
  const editorBadgeOf = (blockId) => {
    const memberId =
      lastEditors[blockId] ??
      items[blockId]?.lastEditedById ??
      items[blockId]?.authorId;
    if (memberId == null) return null;
    const member = boardMembers.find((m) => m.memberId === memberId);
    if (!member) return null;
    return {
      id: memberId,
      name: member.nickname,
      profileImg: member.profileImg ?? null,
    };
  };

  // Day 탭에 찍을 "이 Day 를 보는 중" 멤버들 (커서 하트비트 기반).
  // 프로필 이미지까지 실어 탭에 아바타로 띄운다 — 테두리는 커서와 같은 멤버 색.
  const dayViewersOf = (dayKey) => {
    const dayNo = dayNoOf(dayKey);
    return Object.entries(viewingDays)
      .filter(([, d]) => d === dayNo)
      .map(([id]) => {
        const memberId = Number(id);
        const member = boardMembers.find((m) => m.memberId === memberId);
        return {
          id: memberId,
          name: member?.nickname ?? "다른 멤버",
          profileImg: member?.profileImg ?? null,
        };
      });
  };

  return {
    boardMembers,
    setBoardMembers,
    onlineIds,
    setOnlineIds,
    detailLocks,
    setLastEditors,
    recordBlockEditor,
    nicknameOf,
    lockBadgeOf,
    editorBadgeOf,
    dayViewersOf,
    handlePageCursorMove,
    registerTlCursorHandler,
    registerPageCursorHandler,
    applyPresenceMessage,
    applyCursorMessage,
    pageDOMRef,
  };
}
