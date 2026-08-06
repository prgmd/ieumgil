import { create } from "zustand";

/**
 * 전역 로딩 표시기 — "응답이 늦는 요청이 있다"만 나타낸다.
 *
 * 요청 수를 세는 쪽은 axiosInstance 의 인터셉터이고, 이 스토어는 "언제 보일지"를
 * 정한다. 두 시간 상수가 요점이다:
 *
 *  - SHOW_DELAY_MS: 이 안에 끝난 요청은 아예 표시하지 않는다. 대부분의 호출은
 *    수십 ms 라 곧이곧대로 띄우면 화면이 계속 깜빡이는 것처럼 보인다.
 *  - MIN_VISIBLE_MS: 일단 떴으면 최소 이만큼은 유지한다. 임계 시간 직후에 끝난
 *    요청이 스피너를 한 프레임만 그리고 사라지는 것을 막는다.
 *
 * 카운터라서 동시 요청도 자연히 처리된다 — 마지막 하나가 끝날 때 0 이 된다.
 */
const SHOW_DELAY_MS = 600;
const MIN_VISIBLE_MS = 400;

let showTimer = null;
let hideTimer = null;
let shownAt = 0;

export const useLoadingStore = create((set, get) => ({
  pending: 0, // 진행 중인 "표시 대상" 요청 수 (silent 요청은 세지 않는다)
  visible: false,

  begin: () => {
    set({ pending: get().pending + 1 });
    // 이미 떠 있거나 대기 타이머가 도는 중이면 새로 걸지 않는다 — 뒤이어 시작한
    // 요청이 임계 시간을 처음부터 다시 세면, 정작 오래 걸리는 첫 요청이 계속
    // 밀려 영영 표시되지 않는다.
    if (get().visible || showTimer != null) return;
    showTimer = setTimeout(() => {
      showTimer = null;
      if (get().pending === 0) return; // 그 사이 다 끝났다 — 띄울 이유가 없다
      shownAt = Date.now();
      set({ visible: true });
    }, SHOW_DELAY_MS);
  },

  end: () => {
    const pending = Math.max(0, get().pending - 1);
    set({ pending });
    if (pending > 0) return; // 아직 남은 요청이 있다 — 계속 보여준다

    clearTimeout(showTimer);
    showTimer = null;
    if (!get().visible) return; // 임계 시간 전에 끝났다 — 뜬 적이 없다

    const shownFor = Date.now() - shownAt;
    if (shownFor >= MIN_VISIBLE_MS) {
      set({ visible: false });
      return;
    }
    // 최소 노출 시간이 남았다 — 그만큼만 더 두고 내린다. 그 사이 새 요청이
    // 시작되면 아래 pending 검사가 유지 쪽으로 판단한다.
    clearTimeout(hideTimer);
    hideTimer = setTimeout(() => {
      hideTimer = null;
      if (get().pending === 0) set({ visible: false });
    }, MIN_VISIBLE_MS - shownFor);
  },
}));
