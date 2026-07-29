import { useState } from 'react';

// Drop the real dashboard screenshot into `frontend/public/dashboard-preview.png`
// once the dashboard page is built. No code changes needed — this component
// picks it up automatically and only shows the placeholder while the file
// is missing.
const PREVIEW_SRC = '/dashboard-preview.png';

export function DashboardPreview() {
  const [imageFailed, setImageFailed] = useState(false);

  return (
    <div className="dashboard-preview">
      <div className="dashboard-preview__chrome">
        <div className="dashboard-preview__dots">
          <span className="dashboard-preview__dot dashboard-preview__dot--red" />
          <span className="dashboard-preview__dot dashboard-preview__dot--yellow" />
          <span className="dashboard-preview__dot dashboard-preview__dot--green" />
        </div>
        <span className="dashboard-preview__url">🔒 ieumgil.com/trip/busan</span>
      </div>

      <div className="dashboard-preview__body">
        {imageFailed ? (
          <div className="dashboard-preview__placeholder">
            <span className="dashboard-preview__placeholder-icon" aria-hidden="true">
              🗺️
            </span>
            <p>대시보드 화면을 준비하고 있어요</p>
            <span>대시보드 페이지가 완성되면 이 자리에 실제 화면이 표시돼요.</span>
          </div>
        ) : (
          <img
            src={PREVIEW_SRC}
            alt="이음길 대시보드 미리보기"
            onError={() => setImageFailed(true)}
          />
        )}
      </div>
    </div>
  );
}
