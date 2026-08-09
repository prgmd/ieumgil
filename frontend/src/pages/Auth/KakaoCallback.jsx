import { useEffect } from "react";
import { useSearchParams, useNavigate } from "react-router-dom";
import { useAuthStore } from "../../global/stores/authStore";
import { useToastStore } from "../../global/stores/toastStore";
import { ROUTES } from "../../global/constants/routes";
import { LoadingScreen } from "../../global/components/LoadingScreen";

/**
 * 이미 처리에 착수한 인가코드. 인가코드는 1회용이라 두 번 보내면 두 번째는 반드시 실패한다.
 *
 * 컴포넌트 밖(모듈 스코프)에 두는 이유 — useRef 는 인스턴스가 살아 있는 동안만 유효해서,
 * 이 컴포넌트가 언마운트·리마운트되면 초기값으로 돌아간다. 그때 URL 의 code 는 아직
 * 남아 있으므로 두 번째 요청이 나간다. App 의 부트스트랩 게이트를 고쳐 그 리마운트는
 * 없앴지만, 1회용 자원을 지키는 가드가 마운트 횟수에 의존해서는 안 된다.
 */
let handledCode = null;

function KakaoCallback() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const code = searchParams.get("code");
  const login = useAuthStore((s) => s.login);
  const showToast = useToastStore((s) => s.show);

  useEffect(() => {
    // 같은 코드로는 한 번만 요청한다 (StrictMode 의 effect 2회 실행, 리마운트 모두 차단)
    if (code && handledCode !== code) {
      handledCode = code;

      // 인가 코드 전송 → accessToken 저장 → 내 정보 조회까지 스토어가 처리한다.
      // (refreshToken 은 백엔드가 httpOnly 쿠키로 내려주므로 프론트가 다루지 않는다.)
      login(code)
        // 이미 사용한 인가 코드가 붙은 이 URL 로 뒤로가기 되지 않도록 replace 로 이동
        .then(() => navigate(ROUTES.my, { replace: true }))
        .catch((error) => {
          console.error("백엔드 로그인 처리 실패:", error);
          // 여기서 멈추면 "처리 중" 화면에 갇힌다. 인가코드 만료·재사용(AUTH401_4)은
          // 다시 로그인하면 풀리므로 로그인 페이지로 되돌린다.
          // 서버가 사유를 한국어 message 로 주므로 그대로 보여준다.
          showToast(error?.message ?? "로그인에 실패했어요. 다시 시도해주세요.");
          navigate(ROUTES.login, { replace: true });
        });
    }
  }, [code, login, navigate, showToast]);

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        minHeight: "100vh",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: "18px",
        background:
          "linear-gradient(150deg, #fdf6ea 0%, #f4ecd9 45%, #efe7d2 100%)",
        color: "#3d2b22",
        fontFamily:
          'var(--font-app), "Noto Sans KR", -apple-system, BlinkMacSystemFont, sans-serif',
      }}
    >
      <LoadingScreen label="로그인 중이에요…" />
    </div>
  );
}

export default KakaoCallback;
