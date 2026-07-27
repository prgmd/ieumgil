import React from "react";
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

  const handleNaverLogin = () => {
    alert("네이버 로그인은 추후 구현 예정입니다.");
  };

  return (
    <div className="auth-screen">
      <div className="hero">
        <div className="mark">&#9992;</div>
        <div className="logo-big">이음길</div>
        <p className="tag">
          흩어진 계획을 하나로.
          <br />
          팀이 함께 만들어가는 여행의 길.
        </p>

        <button className="login-btn kakao" onClick={handleKakaoLogin}>
          <span className="ic">K</span>카카오로 시작하기
        </button>

        <button className="login-btn naver" onClick={handleNaverLogin}>
          <span className="ic">N</span>네이버로 시작하기
        </button>

        <p className="small">소셜 로그인만 지원 — 자체 회원가입 없음</p>
      </div>
    </div>
  );
}
