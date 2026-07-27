import { Outlet } from "react-router-dom";

/**
 * 공통 레이아웃 컴포넌트.
 * - 실제 라우트 정의는 router.jsx 한곳에서 관리한다.
 * - 여기서는 하위 라우트를 <Outlet />으로 렌더하며,
 *   추후 모든 페이지 공통 UI(전역 헤더/푸터 등)가 필요하면 이 컴포넌트에 추가한다.
 */
function App() {
  return <Outlet />;
}

export default App;
