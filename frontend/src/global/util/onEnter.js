/**
 * Enter 키 핸들러 — 한글 등 조합형 입력(IME) 중에 Enter 로 조합을 확정할 때도
 * keydown 이벤트가 발생한다. 이때 e.nativeEvent.isComposing 을 확인하지 않으면
 * 조합이 끝나기 전에 제출이 일어난다.
 */
export const onEnter = (handler) => (e) => {
  if (e.key !== "Enter" || e.nativeEvent.isComposing) return;
  handler(e);
};
