import { useEffect } from "react";
import { Outlet } from "react-router-dom";
import { useAuthStore } from "../global/stores/authStore";
import { Toast } from "../global/components/Toast";

/**
 * 공통 레이아웃 컴포넌트.
 * - 실제 라우트 정의는 router.jsx 한곳에서 관리한다.
 * - 여기서는 하위 라우트를 <Outlet />으로 렌더하며,
 *   추후 모든 페이지 공통 UI(전역 헤더/푸터 등)가 필요하면 이 컴포넌트에 추가한다.
 * - 인증 부트스트랩도 여기서 한다 (아래 참고).
 */
function App() {
  const status = useAuthStore((s) => s.status);
  const fetchMe = useAuthStore((s) => s.fetchMe);

  // 인증 부트스트랩 — 토큰은 localStorage 에 남지만 스토어는 메모리라서,
  // full page load(새로고침·딥링크·탭 새로 열기) 직후엔 currentUser 가 비어 있다.
  // 이 effect 가 그때 한 번 채운다. 라우트 이동으로는 App 이 언마운트되지 않으므로
  // 페이지를 옮겨 다녀도 다시 호출되지 않는다 (스토어에 있으면 그대로 쓴다).
  useEffect(() => {
    fetchMe();
  }, [fetchMe]);

  // 내 정보를 받아오는 동안 하위 페이지를 렌더하지 않는다 —
  // 각 페이지가 currentUser 가 채워져 있다고 가정할 수 있게 하기 위함.
  if (status === "loading") {
    return (
      <div style={{ padding: "50px", textAlign: "center" }}>불러오는 중…</div>
    );
  }

  return (
    <>
      <Outlet />
      <Toast />
    </>
  );
}

export default App;
