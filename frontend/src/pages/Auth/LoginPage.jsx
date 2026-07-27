import "./Login.css"; // 동일 폴더에 생성할 CSS 파일 임포트

export function LoginPage() {
  console.log("=== 환경변수 로드 테스트 ===");
  console.log("REST API KEY:", import.meta.env.VITE_KAKAO_REST_API_KEY);
  console.log("REDIRECT URI:", import.meta.env.VITE_KAKAO_REDIRECT_URI);

  const handleKakaoLogin = () => {
    const REST_API_KEY = import.meta.env.VITE_KAKAO_REST_API_KEY;
    const REDIRECT_URI = import.meta.env.VITE_KAKAO_REDIRECT_URI;

    if (!REST_API_KEY || !REDIRECT_URI) {
      console.error(
        "환경변수(.env)에 카카오 API 키와 REDIRECT_URI를 설정해주세요.",
      );
      return;
    }

    const kakaoURL = `https://kauth.kakao.com/oauth/authorize?client_id=${REST_API_KEY}&redirect_uri=${REDIRECT_URI}&response_type=code`;

    window.location.href = kakaoURL;
  };

  return (
    <div className="login-page">
      <div className="login-frame">
        {/* ===== 좌측: 브랜드 히어로 ===== */}
        <aside className="login-hero">
          <ul className="login-hero__icons" aria-hidden="true">
            <li className="login-hero__icon login-hero__icon--plane">✈</li>
            <li className="login-hero__icon login-hero__icon--compass">🧭</li>
            <li className="login-hero__icon login-hero__icon--calendar">📋</li>
            <li className="login-hero__icon login-hero__icon--camera">📷</li>
          </ul>

          <div className="login-hero__content">
            <span className="login-hero__badge">여 행 계 획 서 비 스</span>
            <h1 className="login-hero__title">
              여행을 잇는 길,
              <br />
              <span className="login-hero__accent">
                이음길
                <svg
                  className="login-hero__underline"
                  viewBox="0 0 220 20"
                  fill="none"
                  xmlns="http://www.w3.org/2000/svg"
                  aria-hidden="true"
                >
                  <path
                    d="M4 14C40 4 80 4 110 10C140 16 180 16 216 6"
                    stroke="var(--acc)"
                    strokeWidth="6"
                    strokeLinecap="round"
                  />
                </svg>
              </span>
            </h1>
            <p className="login-hero__subtitle">
              친구들과 함께 세부 일정을 만들고, AI에게 추천 받으며,
              <br />
              모든 계획을 한 곳에서 완벽하게 관리하세요.
            </p>
          </div>
        </aside>

        {/* ===== 우측: 로그인 ===== */}
        <section className="login-panel">
          <header className="login-panel__nav">
            <div className="login-panel__brand">
              <span aria-hidden="true">✈</span>이음길
            </div>
          </header>

          <div className="login-panel__body">
            <div className="login-card">
              <h2 className="login-card__title">환영합니다</h2>
              <p className="login-card__subtitle">다시 여행을 이어가볼까요?</p>

              <button className="login-btn kakao" onClick={handleKakaoLogin}>
                <span className="login-btn__ic" aria-hidden="true">
                  <svg viewBox="0 0 256 256" width="20" height="20" fill="currentColor">
                    <path d="M128 36C70.562 36 24 72.713 24 118c0 29.279 19.466 54.97 48.748 69.477-1.593 5.494-10.237 35.344-10.581 37.689 0 0-.207 1.762.934 2.434 1.14.672 2.483.15 2.483.15 3.272-.457 37.943-24.811 43.937-28.996 6.049.855 12.276 1.296 18.479 1.296 57.438 0 104-36.712 104-82 0-45.287-46.562-82-104-82" />
                  </svg>
                </span>
                카카오로 시작하기
              </button>
            </div>

            <p className="login-panel__terms">
              로그인 시 <a href="#">이용약관</a> 및{" "}
              <a href="#">개인정보처리방침</a>에 동의하게 됩니다.
            </p>
          </div>
        </section>
      </div>
    </div>
  );
}
