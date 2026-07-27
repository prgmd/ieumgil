import { useEffect, useRef } from "react";
import { useSearchParams, useNavigate } from "react-router-dom";
import axios from "axios"; // 또는 fetch 사용

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
      axios
        .post("http://localhost:8080/api/v0/auth/login/kakao", { code })
        .then((response) => {
          console.log("로그인 성공:", response.data);

          // 백엔드에서 받은 JWT 토큰 저장 (예: accessToken)
          const { accessToken, refreshToken } = response.data;
          localStorage.setItem("accessToken", accessToken);
          // refreshToken도 저장하거나 쿠키로 받아 처리

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
