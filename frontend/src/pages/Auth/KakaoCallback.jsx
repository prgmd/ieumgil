import { useEffect, useRef } from "react";
import { useSearchParams, useNavigate } from "react-router-dom";
import { useAuthStore } from "../../global/stores/authStore";

function KakaoCallback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const code = searchParams.get("code");
  const login = useAuthStore((s) => s.login);

  // React 18+ strict mode에서 API가 2번 연속 호출되는 것을 방지하기 위한 flag
  // (인가 코드는 1회용이라 두 번째 호출은 반드시 실패한다)
  const isFetched = useRef(false);

  useEffect(() => {
    if (code && !isFetched.current) {
      isFetched.current = true;

      // 인가 코드 전송 → accessToken 저장 → 내 정보 조회까지 스토어가 처리한다.
      // (refreshToken 은 백엔드가 httpOnly 쿠키로 내려주므로 프론트가 다루지 않는다.)
      login(code)
        // 이미 사용한 인가 코드가 붙은 이 URL 로 뒤로가기 되지 않도록 replace 로 이동
        .then(() => navigate("/my", { replace: true }))
        .catch((error) => {
          console.error("백엔드 로그인 처리 실패:", error);
          // 에러 발생 시 로그인 페이지로 돌아가기
          // navigate('/login');
        });
    }
  }, [code, login, navigate]);

  return (
    <div style={{ padding: "50px", textAlign: "center" }}>
      <h2>카카오 로그인 처리 중입니다...</h2>
    </div>
  );
}

export default KakaoCallback;
