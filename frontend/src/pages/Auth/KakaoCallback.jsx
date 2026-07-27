import { useEffect, useRef } from "react";
import { useSearchParams, useNavigate } from "react-router-dom";
import { loginWithKakao } from "../../features/auth/api/authApi";
import { tokenStorage } from "../../global/util/tokenStorage";

function KakaoCallback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const code = searchParams.get("code");

  // React 18+ strict mode에서 API가 2번 연속 호출되는 것을 방지하기 위한 flag
  const isFetched = useRef(false);

  useEffect(() => {
    if (code && !isFetched.current) {
      isFetched.current = true;

      // 백엔드로 인가 코드 전송
      loginWithKakao(code)
        .then(({ accessToken }) => {
          // accessToken 만 저장 (refreshToken 은 백엔드가 httpOnly 쿠키로 내려준다)
          // 이후 요청은 인터셉터가 자동으로 accessToken 을 헤더에 삽입한다.
          tokenStorage.setAccessToken(accessToken);

          // 로그인 완료 후 메인 페이지 등으로 이동
          navigate("/");
        })
        .catch((error) => {
          console.error("백엔드 로그인 처리 실패:", error);
          // 에러 발생 시 로그인 페이지로 돌아가기
          // navigate('/login');
        });
    }
  }, [code, navigate]);

  return (
    <div style={{ padding: "50px", textAlign: "center" }}>
      <h2>카카오 로그인 처리 중입니다...</h2>
    </div>
  );
}

export default KakaoCallback;
