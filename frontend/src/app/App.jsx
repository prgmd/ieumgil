import { useEffect, useState } from "react";
import { Outlet } from "react-router-dom";
import { useAuthStore } from "../global/stores/authStore";
import { Toast } from "../global/components/Toast";
import { GlobalSpinner } from "../global/components/GlobalSpinner";
import { LoadingScreen } from "../global/components/LoadingScreen";

/**
 * 공통 레이아웃 컴포넌트.
 * - 실제 라우트 정의는 router.jsx 한곳에서 관리한다.
 * - 여기서는 하위 라우트를 <Outlet />으로 렌더하며,
 *   추후 모든 페이지 공통 UI(전역 헤더/푸터 등)가 필요하면 이 컴포넌트에 추가한다.
 * - 인증 부트스트랩도 여기서 한다 (아래 참고).
 */
function App() {
  const fetchMe = useAuthStore((s) => s.fetchMe);

  // 부트스트랩 완료 여부. status 로 게이트하지 않는 이유가 중요하다 —
  // status 는 fetchMe() 를 다시 부르는 코드가 생기면 부트스트랩 이후에도 "loading" 이
  // 될 수 있다. 거기에 게이트를 걸면 그 순간 <Outlet/> 이 내려가 현재 페이지가
  // 언마운트되고, 끝나면 **새 인스턴스로 다시 마운트**된다(useRef 도 초기화). 그러면
  // 그 페이지에서 돌던 1회용 처리가 두 번 실행된다.
  //
  // 실제로 login() 이 내부에서 fetchMe() 를 부르던 동안 이 버그가 있었다 — 카카오
  // 인가코드 교환이 두 번 나가 두 번째가 KOE320 으로 실패했다. 지금은 login() 이
  // fetchMe() 를 부르지 않지만, 게이트를 status 에 되돌리면 같은 일이 재발한다.
  //
  // 그래서 이 값은 한 번 true 가 되면 다시 false 가 되지 않는다.
  const [booted, setBooted] = useState(false);

  // 인증 부트스트랩 — 토큰은 localStorage 에 남지만 스토어는 메모리라서,
  // full page load(새로고침·딥링크·탭 새로 열기) 직후엔 currentUser 가 비어 있다.
  // 이 effect 가 그때 한 번 채운다. 라우트 이동으로는 App 이 언마운트되지 않으므로
  // 페이지를 옮겨 다녀도 다시 호출되지 않는다 (스토어에 있으면 그대로 쓴다).
  useEffect(() => {
    fetchMe().finally(() => setBooted(true));
  }, [fetchMe]);

  // 부트스트랩이 끝나기 전에는 하위 페이지를 렌더하지 않는다 —
  // 각 페이지가 currentUser 가 채워져 있다고 가정할 수 있게 하기 위함.
  // (자식은 이 시점 이후 딱 한 번 마운트된다.)
  if (!booted) {
    return <LoadingScreen full />;
  }

  return (
    <>
      <Outlet />
      <Toast />
      {/* 늦는 요청에만 뜨는 전역 스피너 — 부트스트랩 화면(위 !booted)에는
          이미 자체 안내가 있으므로 이 아래로만 둔다 */}
      <GlobalSpinner />
    </>
  );
}

export default App;
