import React from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthPage } from "../pages/Auth/LoginPage";
import KakaoCallback from "../pages/Auth/KakaoCallback";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 기본 경로(/)로 접속했을 때 AuthPage를 보여줍니다. */}
        <Route path="/" element={<AuthPage />} />
        <Route path="/oauth/kakao/callback" element={<KakaoCallback />} />
        {/* 나중에 필요한 페이지들을 아래처럼 계속 추가할 수 있습니다. */}
        {/* <Route path="/dashboard" element={<DashboardPage />} /> */}
      </Routes>
    </BrowserRouter>
  );
}

export default App;
