// pages/Dashboard/hooks/useDayNav.js
//
// Day 네비게이션 — 스크롤 위치에서 활성 Day 를 파생하고(dominantDayOf), 탭 클릭 시
// 그 Day 로 스크롤 점프한다(jumpToDay). 점프가 도는 동안엔 클릭이 이겨서
// 지나가는 중간 Day 들이 하이라이트를 흔들지 않게 락을 건다(jumpLockRef).
//
// scrolledDay/activeDay state 는 부모(index.jsx)가 소유한다 — activeDay 가
// 타임라인 DOM 이 만들어지기 전(렌더 초반)에 이미 파생·소비되기 때문이다.
// 이 훅은 그 setter 와 파생값을 인자로 받아 스크롤 기계장치만 맡는다.

import { useRef } from "react";
import * as blockApi from "../../../features/dashboard/api/dashboardApi";
import { dayNoOf, firstStartOf, PX, TL_PAD_TOP } from "../dashboardHelpers";

export function useDayNav({
  setScrolledDay,
  activeDay,
  dayKeys,
  board,
  items,
  timelineStart,
  timelineEnd,
  timelineDOMRef,
}) {
  // ── 활성 Day 파생 = 뷰포트를 가장 많이 차지하는 Day ─────────
  /**
   * 뷰포트가 덮는 분 구간을 각 Day 의 [i*1440, (i+1)*1440) 과 교차시켜, 겹침이
   * 가장 넓은 Day 키를 준다. 경계에 걸쳐 있으면 화면을 더 많이 차지한 쪽이 이긴다.
   * 분 → px 환산은 보드 어디서나 (분 - timelineStart) * PX + TL_PAD_TOP 이다
   * (computeDropTarget 의 역산과 같은 식이라 여백 항까지 맞춰 둔다).
   */
  const dominantDayOf = (scrollTop, viewportH) => {
    const from = (scrollTop - TL_PAD_TOP) / PX + timelineStart;
    const to = (scrollTop + viewportH - TL_PAD_TOP) / PX + timelineStart;
    // 겹치는 Day 가 하나도 없을 수 있다 — 마지막 자정 너머로 크게 밀린 블록 때문에
    // 컨테이너가 축보다 길어지면, 그 꼬리에서는 모든 겹침이 음수다. 그때 Day 1 로
    // 떨어지면 활성 Day 가 튀면서 창이 좁아져 보고 있던 꼬리가 통째로 언마운트된다.
    // 뷰포트가 축 뒤쪽에 있으면 마지막 Day 를, 앞쪽이면 첫 Day 를 고른다.
    let best = from >= timelineEnd ? dayKeys[dayKeys.length - 1] : dayKeys[0];
    let bestOverlap = -Infinity;
    dayKeys.forEach((day, i) => {
      const dayFrom = i * blockApi.MINUTES_PER_DAY;
      const overlap =
        Math.min(to, dayFrom + blockApi.MINUTES_PER_DAY) -
        Math.max(from, dayFrom);
      if (overlap > bestOverlap) {
        bestOverlap = overlap;
        best = day;
      }
    });
    return best;
  };

  // 탭 점프가 도는 동안엔 클릭이 이긴다 — 부드러운 스크롤이 지나가는 중간 Day 들이
  // 하이라이트를 흔들고 presence 를 헛되이 쏘는 것을 막는다. 목표 Day 에 닿으면
  // (=스크롤이 거기 도착했다) 그 자리에서 풀고, 사용자가 도중에 직접 스크롤을
  // 가로채 목표에 영영 안 닿는 경우는 타이머가 풀어 준다.
  const jumpLockRef = useRef(null); // 점프 목표 dayKey
  const jumpTimerRef = useRef(0);
  const dominantRafRef = useRef(0);
  const releaseJumpLock = () => {
    jumpLockRef.current = null;
    clearTimeout(jumpTimerRef.current);
  };
  const syncDominantDay = () => {
    const el = timelineDOMRef.current;
    if (!el) return;
    const next = dominantDayOf(el.scrollTop, el.clientHeight);
    if (jumpLockRef.current) {
      if (next !== jumpLockRef.current) return;
      releaseJumpLock();
    }
    setScrolledDay((prev) => (prev === next ? prev : next));
  };
  // 스크롤 이벤트마다 재지 않는다 — 같은 핸들러가 드래그 중 computeDropTarget 까지
  // 부르므로, Day 계산은 프레임당 한 번으로 묶는다.
  const scheduleDominantDay = () => {
    if (dominantRafRef.current) return;
    dominantRafRef.current = requestAnimationFrame(() => {
      dominantRafRef.current = 0;
      syncDominantDay();
    });
  };

  /**
   * 그 Day 의 첫 블록으로 스크롤한다. 배치된 블록이 없으면 그 Day 의 00:00 으로.
   * Day 별 스크롤 위치 기억은 없앴다 — 스크롤 컨테이너가 하나라 위치도 하나다.
   * behavior 를 주지 않으면 **거리가 정한다**(아래).
   */
  const jumpToDay = (dayKey, behavior) => {
    const el = timelineDOMRef.current;
    if (!el) return;
    const base = (dayNoOf(dayKey) - 1) * blockApi.MINUTES_PER_DAY;
    const target = firstStartOf(board, items, dayKey) ?? base;
    // 15분(=30px)만 앞에서 멈춘다 — 목표가 sticky Day 머리글에 가리지 않게
    const top = Math.max(0, (target - timelineStart - 15) * PX);
    // 부드럽게 흘릴지 즉시 뛸지는 거리가 정한다. 기준은 렌더 창의 정의와 같은
    // ±1 Day 다 — 창 안이면 지나갈 Day 가 이미 마운트돼 있어 부드럽게 가도
    // 중간이 비지 않는다. 창 밖이면 지나가는 Day 들에 눈금도 카드도 없어
    // 빈 복도를 흘려보내게 되고, 애초에 Day 1 → Day 30 은 86,520px 라
    // 부드럽게 볼 거리가 아니다. 그래서 창 밖은 즉시 뛴다 — 복도가 생길 수
    // 있는 경우 자체가 없어진다.
    const withinWindow = Math.abs(dayNoOf(dayKey) - dayNoOf(activeDay)) <= 1;
    const how = behavior ?? (withinWindow ? "smooth" : "auto");
    setScrolledDay(dayKey);
    // 이미 그 자리면 스크롤 이벤트가 안 난다 — 잠그지 않고, 앞선 점프의 락이
    // 남아 있으면 여기서 푼다(위치가 이미 목표라 방금 넣은 값이 곧 정답이다)
    if (Math.abs(el.scrollTop - top) < 1) {
      releaseJumpLock();
      return;
    }
    clearTimeout(jumpTimerRef.current);
    jumpLockRef.current = dayKey;
    // 시한이 다하면 락을 풀고 **그 자리에서 다시 잰다.** 풀기만 하면 안 된다 —
    // 락이 걸린 동안의 스크롤 이벤트는 syncDominantDay 의 조기 반환에 전부
    // 삼켜지므로, 사용자가 휠로 점프를 가로채고 멈춰 서면(스무스 스크롤은
    // 취소된다) 락이 풀린 뒤에도 이벤트가 더는 오지 않는다. 그러면 하이라이트가
    // 목표 Day 에 얼어붙고 presence·커서 dayNo·지도까지 그 Day 를 계속 방송해
    // 팀원에게 내가 있지도 않은 Day 를 보고 있다고 거짓말한다. 최대 스크롤
    // 위치라 scrollTo 가 조용한 no-op 이 되는 경우도 같은 길로 구제된다.
    jumpTimerRef.current = setTimeout(() => {
      if (jumpLockRef.current !== dayKey) return;
      jumpLockRef.current = null;
      syncDominantDay();
    }, 1200);
    el.scrollTo({ top, behavior: how });
  };

  return { jumpToDay, scheduleDominantDay };
}
