import { useSyncExternalStore } from "react";

/**
 * 모바일 화면인지. 대시보드가 "편집 가능한 화면인가"를 가르는 데 쓴다.
 *
 * userAgent 가 아니라 화면 폭으로 판정한다 — 편집(드래그·리사이즈)이 막히는 건
 * 기기 종류가 아니라 "좁고 손가락으로 만지는 화면"이라서다. 데스크톱 창을 좁혀도
 * 같은 판정이 나오는 편이 QA 하기도 쉽다.
 *
 * useState + useEffect 대신 useSyncExternalStore 를 쓴다 — matchMedia 는 React
 * 밖의 외부 스토어라 이 API 가 정확히 그 용도이고, "첫 렌더와 구독 사이에 값이
 * 바뀌는" 틈을 React 가 알아서 메운다(직접 짜면 effect 안에서 setState 를 부르게 된다).
 *
 * 폭 기준은 768px — 세로 태블릿까지 모바일로 본다.
 */
const MOBILE_QUERY = "(max-width: 768px)";

const subscribe = (onStoreChange) => {
  const mql = window.matchMedia?.(MOBILE_QUERY);
  if (!mql) return () => {};
  // 창 크기 변경뿐 아니라 기기 회전(세로↔가로)도 이 이벤트로 들어온다
  mql.addEventListener("change", onStoreChange);
  return () => mql.removeEventListener("change", onStoreChange);
};

// 불리언(원시값)이라 매번 새로 읽어도 같은 값이면 리렌더가 일어나지 않는다
const getSnapshot = () => window.matchMedia?.(MOBILE_QUERY).matches ?? false;

export function useIsMobile() {
  return useSyncExternalStore(subscribe, getSnapshot);
}
