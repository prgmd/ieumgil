import { useEffect, useState } from "react";
import { hueOf } from "./memberColor";

/** 이 시간 동안 갱신이 없으면 커서를 걷는다 — 이탈 메시지가 유실돼도 잔상이 안 남게 */
const EXPIRE_MS = 5000;

/**
 * 다른 멤버들의 라이브 커서 (7단계).
 *
 * 성능 격리가 이 컴포넌트의 존재 이유다 — 커서는 초당 수십 건씩 오는데, 그 상태를
 * 대시보드 본체에 두면 매 메시지마다 보드 전체(타임라인·후보·예산)가 리렌더된다.
 * 여기 가두면 이 레이어만 다시 그린다.
 *
 * 커서 메시지 계약(송신 측 handlePageCursorMove 와 짝) — area 로 좌표 공간을 구분한다:
 *   { area: "tl",   x: 타임라인 가로 비율(0~1), y: 분(minutes), dayNo }
 *   { area: "page", x: 페이지 가로 비율(0~1),   y: 페이지 세로 비율(0~1), dayNo }
 *   { area: "view", dayNo }   ← 위치 없는 하트비트(Day 탭 표시용) — 커서를 안 건드린다
 *   { area: "leave", dayNo }  ← 대시보드 이탈 — 커서를 걷는다
 * "tl" 의 y 가 픽셀이 아니라 시각(분)인 이유: 상대와 내 스크롤·타임라인 시작이
 * 달라도 "같은 시간 위치"에 그려진다.
 *
 * 한 멤버의 커서는 한 레이어에만 존재한다 — 내 mode 와 다른 area 메시지가 오면
 * (다른 영역으로 이동) 이 레이어에서 걷는 것으로 이동을 표현한다.
 *
 * @param {(handler: (msg) => void) => void} register 수신 콜백 등록(latest-ref 주입)
 * @param {number|undefined} myId 자기 커서 스킵 기준(actorId 비교 — 계약상 clientId 아님)
 * @param {"tl"|"page"} mode 이 레이어가 그리는 좌표 공간
 * @param {number} [activeDayNo] tl 전용 — 지금 보는 Day. 다른 Day 의 커서는 그리지 않는다
 *   (page 커서는 후보 목록·사이드 등 Day 와 무관한 영역이라 항상 그린다)
 * @param {(memberId: number) => string} nicknameOf 커서에 붙일 이름표
 */
export function RemoteCursorLayer({
  register,
  myId,
  mode,
  activeDayNo,
  timelineStart,
  px,
  padTop,
  padLeft,
  nicknameOf,
}) {
  const [cursors, setCursors] = useState({}); // actorId → { x, y, dayNo, at }

  useEffect(() => {
    register((msg) => {
      if (msg?.actorId == null || msg.actorId === myId) return;
      setCursors((prev) => {
        if (msg.area === mode) {
          return {
            ...prev,
            [msg.actorId]: {
              x: msg.x,
              y: msg.y,
              dayNo: msg.dayNo,
              at: Date.now(),
            },
          };
        }
        // view 하트비트는 위치 정보가 아니다 — 커서를 건드리지 않는다
        if (msg.area === "view" || !(msg.actorId in prev)) return prev;
        // 다른 영역으로 이동("tl"↔"page")했거나 페이지를 떠남("leave") — 걷는다
        const next = { ...prev };
        delete next[msg.actorId];
        return next;
      });
    });
    return () => register(() => {});
  }, [register, myId, mode]);

  // 만료 청소 — 상대가 이탈 메시지 없이 사라진 경우(탭 강제 종료 등)의 잔상 방지
  useEffect(() => {
    const timer = setInterval(() => {
      setCursors((prev) => {
        const now = Date.now();
        const alive = Object.entries(prev).filter(
          ([, c]) => now - c.at < EXPIRE_MS,
        );
        return alive.length === Object.keys(prev).length
          ? prev
          : Object.fromEntries(alive);
      });
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  const visible = Object.entries(cursors).filter(
    ([, c]) => mode === "page" || c.dayNo === activeDayNo,
  );

  return (
    <div
      className="cursor-layer"
      // tl: 눈금(.tl-bg)·카드(.tl-slots)와 같은 오프셋에 앉아 같은 top 공식을 쓴다
      // page: 대시보드 컨테이너 전체를 덮는다
      style={
        mode === "tl"
          ? { top: padTop, left: padLeft, right: 0, bottom: 0 }
          : { inset: 0 }
      }
    >
      {visible.map(([actorId, c]) => (
        <div
          key={actorId}
          className="remote-cursor"
          style={{
            left: `${Math.max(0, Math.min(1, c.x)) * 100}%`,
            top:
              mode === "tl"
                ? `${(c.y - timelineStart) * px}px`
                : `${Math.max(0, Math.min(1, c.y)) * 100}%`,
            "--cursor-h": hueOf(actorId),
          }}
        >
          <span className="remote-cursor-dot" />
          <span className="remote-cursor-name">
            {nicknameOf(Number(actorId))}
          </span>
        </div>
      ))}
    </div>
  );
}
