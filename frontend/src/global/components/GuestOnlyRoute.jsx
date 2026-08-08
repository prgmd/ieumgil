import { Navigate, Outlet } from "react-router-dom";
import { tokenStorage } from "../util/tokenStorage";
import { ROUTES } from "../constants/routes";

/**
 * 비로그인 전용 가드 (ProtectedRoute 의 대칭)
 * - accessToken 이 있으면(= 로그인) 개인 페이지("/my")로 리다이렉트한다.
 * - 없으면 하위 라우트(<Outlet />)를 그대로 렌더한다.
 *
 * 랜딩처럼 "로그인 전에 보여줄 페이지"에 붙인다. replace 를 쓰는 이유는
 * 뒤로 가기로 다시 랜딩에 들어와 또 튕기는 왕복을 만들지 않기 위함이다.
 *
 * 주의: 판단 근거는 토큰의 존재뿐이다(ProtectedRoute 와 동일). 만료된 토큰이
 * 남아 있으면 일단 /my 로 보내고, 거기서의 요청이 401 을 받아 재발급까지
 * 실패하면 axiosInstance 인터셉터가 토큰을 지우고 "/" 로 되돌린다.
 */
function GuestOnlyRoute() {
  const isAuthenticated = !!tokenStorage.getAccessToken();

  return isAuthenticated ? <Navigate to={ROUTES.my} replace /> : <Outlet />;
}

export default GuestOnlyRoute;
