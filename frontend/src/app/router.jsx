import { createBrowserRouter, Navigate } from 'react-router-dom';
import App from './App';
import { LandingPage } from '../pages/Landing';
import { LoginPage } from '../pages/Auth/LoginPage';
import KakaoCallback from '../pages/Auth/KakaoCallback';
import { DashboardPage } from '../pages/Dashboard';
import { GroupPage } from '../pages/Group';
import { MyPage } from '../pages/My';
import ProtectedRoute from '../global/components/ProtectedRoute';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />, // 공통 레이아웃 (<Outlet /> 렌더)
    children: [
      // ===== 공개 라우트: 토큰 없이 접근 가능 =====
      { index: true, element: <LandingPage /> }, // 랜딩
      { path: 'login', element: <LoginPage /> }, // 로그인
      // OAuth 콜백: 로그인 완료 전(토큰 없음)에 거치므로 반드시 공개여야 함
      { path: 'oauth/kakao/callback', element: <KakaoCallback /> },

      // ===== 보호 라우트: 토큰 없으면 "/"로 리다이렉트 =====
      {
        element: <ProtectedRoute />, // 인증 가드 (pathless layout route)
        children: [
          { path: 'dashboard', element: <DashboardPage /> },
          { path: 'group', element: <GroupPage /> },
          { path: 'my', element: <MyPage /> },
          // 이후 로그인 필요한 페이지는 이 children 안에 추가하면 자동 보호됨
        ],
      },

      // 정의되지 않은 모든 경로는 랜딩으로 (기본 차단)
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]);
