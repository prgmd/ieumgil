import { useLoadingStore } from "../stores/loadingStore";
import "./spinner.css";

/**
 * 전역 로딩 표시 컴포넌트 — Toast 와 같은 방식으로 App 에 한 번만 마운트한다.
 * 표시 여부는 loadingStore 가 정하고(늦은 요청만), 여기서는 그리기만 한다.
 *
 * 항상 DOM 에 두고 클래스만 바꾼다 — 조건부 마운트로는 사라질 때의 페이드가
 * 재생되지 않는다(토스트와 같은 이유).
 */
export function GlobalSpinner() {
  const visible = useLoadingStore((s) => s.visible);

  return (
    <div
      className={`gspin ${visible ? "gspin--visible" : ""}`}
      role="status"
      aria-live="polite"
      aria-hidden={!visible}
    >
      <span className="gspin__ring" aria-hidden="true" />
      <span>불러오는 중…</span>
    </div>
  );
}
