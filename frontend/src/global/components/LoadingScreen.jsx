import { useEffect } from "react";
import { useLoadingStore } from "../stores/loadingStore";
import loadingImg from "../../assets/img/loading.png";
import "./spinner.css";

// 콘텐츠 영역을 채우는 로딩 표시 — 이음이 캐릭터가 통통 튀며 "불러오는 중"을
// 알린다. 앱 부팅·그룹·마이처럼 화면 단위로 기다리는 자리에 쓴다(전역 스피너는
// 늦는 배경 요청용이라 별개).
export function LoadingScreen({ label = "불러오는 중…", full = false }) {
  // 떠 있는 동안 전역 알약을 억제한다(같은 안내가 상단에 겹치지 않게)
  useEffect(() => {
    const { pushFullscreen, popFullscreen } = useLoadingStore.getState();
    pushFullscreen();
    return () => popFullscreen();
  }, []);

  return (
    <div
      className={`lscreen ${full ? "lscreen--full" : ""}`}
      role="status"
      aria-live="polite"
    >
      <img
        className="lscreen__img"
        src={loadingImg}
        alt=""
        aria-hidden="true"
        width={72}
        height={72}
      />
      <span className="lscreen__label">{label}</span>
    </div>
  );
}
