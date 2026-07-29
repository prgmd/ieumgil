import { createBrowserRouter, Navigate } from 'react-router-dom';
import App from './App';
import { LandingPage } from '../pages/Landing';
import { LoginPage } from '../pages/Auth/LoginPage';
import KakaoCallback from '../pages/Auth/KakaoCallback';
import { DashboardPage } from '../pages/Dashboard';
import { GroupPage } from '../pages/Group';
import { MyPage } from '../pages/My';
import ProtectedRoute from '../global/components/ProtectedRoute';
import GuestOnlyRoute from '../global/components/GuestOnlyRoute';

export const router = createBrowserRouter([
  {
    path: '/',
    element: <App />, // 공통 레이아웃 (<Outlet /> 렌더)
    children: [
      // ===== 비로그인 전용: 토큰이 있으면 "/my"로 리다이렉트 =====
      {
        element: <GuestOnlyRoute />, // 역가드 (pathless layout route)
        children: [
          { index: true, element: <LandingPage /> }, // 랜딩
        ],
      },

      // ===== 공개 라우트: 토큰 없이 접근 가능 =====
      { path: 'login', element: <LoginPage /> }, // 로그인
      // 그룹 페이지는 groupId로 스토어에서 다시 조회한다 (새로고침·딥링크 대응)
      { path: 'oauth/kakao/callback', element: <KakaoCallback /> },

      // ===== 보호 라우트: 토큰 없으면 "/"로 리다이렉트 =====
      {
        element: <ProtectedRoute />, // 인증 가드 (pathless layout route)
        children: [
          { path: 'dashboard', element: <DashboardPage /> },
          { path: 'my', element: <MyPage /> },
          { path: 'groups/:groupId', element: <GroupPage /> },
          // OAuth 콜백: 로그인 완료 전(토큰 없음)에 거치므로 반드시 공개여야 함
          // 이후 로그인 필요한 페이지는 이 children 안에 추가하면 자동 보호됨
        ],
      },

      // 정의되지 않은 모든 경로는 랜딩으로 (기본 차단)
      { path: '*', element: <Navigate to="/" replace /> },
    ],
  },
]);
