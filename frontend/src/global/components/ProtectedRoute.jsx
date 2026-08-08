import { Navigate, Outlet } from "react-router-dom";
import { tokenStorage } from "../util/tokenStorage";
import { ROUTES } from "../constants/routes";

/**
 * 인증 가드 (Authentication Guard)
 * - accessToken 이 없으면(= 비로그인) 로그인/랜딩 페이지("/")로 리다이렉트한다.
 * - 토큰이 있으면 하위 라우트(<Outlet />)를 그대로 렌더한다.
 *
 * 주의: 이 가드는 "로그인 여부"만 판단하는 인증 가드다.
 * "이 그룹의 멤버인가" 같은 인가(권한/소유권)는 백엔드가 403/404 로 판단해야 하며,
 * 프론트 가드로는 막을 수 없다.
 */
function ProtectedRoute() {
  const isAuthenticated = !!tokenStorage.getAccessToken();

  return isAuthenticated ? <Outlet /> : <Navigate to={ROUTES.landing} replace />;
}

export default ProtectedRoute;
