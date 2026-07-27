import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import KakaoCallback from "../pages/Auth/KakaoCallback";
import { DashboardPage } from "../pages/Dashboard";
import { GroupPage } from "../pages/Group";
import { MyPage } from "../pages/My";
import ProtectedRoute from "../global/components/ProtectedRoute";
import { LoginPage } from "../pages/Auth/LoginPage";
import { LandingPage } from "../pages/Landing";

function App() {
  return (
      <Routes>
        {/* ===== 공개 라우트: 토큰 없이 접근 가능 ===== */}
        {/* 로그인 / 랜딩 페이지 */}
        <Route path="/" element={<LandingPage></LandingPage>} />
        <Route path="/login" element={<LoginPage></LoginPage>}></Route>
        {/* OAuth 콜백: 로그인 완료 전(토큰 없음)에 거치므로 반드시 공개여야 함 */}
        <Route path="/oauth/kakao/callback" element={<KakaoCallback />} />

        {/* ===== 보호 라우트: 토큰 없으면 "/"로 리다이렉트 ===== */}
        <Route element={<ProtectedRoute />}>
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/group" element={<GroupPage />} />
          <Route path="/my" element={<MyPage />} />
          {/* 이후 로그인 필요한 페이지는 이 블록 안에 추가하면 자동으로 보호됨 */}
        </Route>

        {/* 정의되지 않은 모든 경로는 로그인/랜딩으로 (기본 차단) */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
  );
}

export default App;
