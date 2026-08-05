import { useRef } from "react";

/**
 * 꾹 누르면 연속 반복되는 버튼 (QA 배치2) — 400ms 홀드 후 100ms 간격 반복.
 * 짧은 탭은 click 한 번이고, 홀드였다면 릴리즈 때 따라오는 click 을 삼켜
 * 마지막에 한 칸 더 가는 이중 실행을 막는다.
 */
export function HoldRepeatButton({ onTrigger, children, ...rest }) {
  const timersRef = useRef({ delay: null, repeat: null });
  const repeatedRef = useRef(false);

  const stop = () => {
    clearTimeout(timersRef.current.delay);
    clearInterval(timersRef.current.repeat);
    timersRef.current = { delay: null, repeat: null };
  };

  const handlePointerDown = () => {
    repeatedRef.current = false;
    timersRef.current.delay = setTimeout(() => {
      repeatedRef.current = true;
      onTrigger();
      timersRef.current.repeat = setInterval(onTrigger, 100);
    }, 400);
  };

  const handleClick = () => {
    if (repeatedRef.current) {
      repeatedRef.current = false;
      return;
    }
    onTrigger();
  };

  return (
    <button
      type="button"
      {...rest}
      onPointerDown={handlePointerDown}
      onPointerUp={stop}
      onPointerLeave={stop}
      onPointerCancel={stop}
      onClick={handleClick}
    >
      {children}
    </button>
  );
}
