import { useRouteError } from "react-router-dom";
import { EmptyState } from "../../global/components/EmptyState";
import { ROUTES } from "../../global/constants/routes";
import "./error.css";

// 라우트 렌더/로더에서 throw 된 예외를 받는 화면 — 사용자에겐 백지 대신
// 이음이와 함께 새로고침·홈 길을 주고, 원인은 개발 참고용으로 접어 둔다.
export function ErrorPage() {
  const error = useRouteError();
  const detail = error?.message ?? String(error ?? "");

  return (
    <div className="epage">
      <EmptyState
        title="문제가 생겼어요"
        desc="잠시 문제가 있었어요. 새로고침하거나 처음 화면으로 돌아가 다시 시도해주세요."
        action={
          <div className="epage__actions">
            <button
              type="button"
              className="btn btn-acc"
              onClick={() => window.location.reload()}
            >
              새로고침
            </button>
            <a className="btn btn-gh" href={ROUTES.landing}>
              홈으로 가기
            </a>
          </div>
        }
      />
      {detail && (
        <details className="epage__detail">
          <summary>자세히</summary>
          <pre>{detail}</pre>
        </details>
      )}
    </div>
  );
}
