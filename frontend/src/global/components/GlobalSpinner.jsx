import { useLoadingStore } from "../stores/loadingStore";
import loadingImg from "../../assets/img/loading.png";
import "./spinner.css";

/**
 * 전역 로딩 표시 컴포넌트 — Toast 와 같은 방식으로 App 에 한 번만 마운트한다.
 * 표시 여부는 loadingStore 가 정하고(늦은 요청만), 여기서는 그리기만 한다.
 *
 * 항상 DOM 에 두고 클래스만 바꾼다 — 조건부 마운트로는 사라질 때의 페이드가
 * 재생되지 않는다(토스트와 같은 이유).
 */
export function GlobalSpinner() {
  // 전체화면 로더가 떠 있으면 알약은 숨긴다(겹침 방지)
  const visible = useLoadingStore((s) => s.visible && s.fullscreen === 0);

  return (
    <div
      className={`gspin ${visible ? "gspin--visible" : ""}`}
      role="status"
      aria-live="polite"
      aria-hidden={!visible}
    >
      <img
        className="gspin__img"
        src={loadingImg}
        alt=""
        aria-hidden="true"
        width={22}
        height={22}
      />
      <span>불러오는 중…</span>
    </div>
  );
}
