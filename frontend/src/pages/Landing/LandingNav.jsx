import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../../global/hooks/useAuth";

export function LandingNav() {
  const [isScrolled, setIsScrolled] = useState(false);
  const { isAuthenticated, currentUser } = useAuth();

  useEffect(() => {
    const onScroll = () => setIsScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <header className={`landing-nav ${isScrolled ? "is-scrolled" : ""}`}>
      <div className="landing-nav__inner">
        <Link to="/" className="landing-nav__brand">
          <span className="landing-nav__mark" aria-hidden="true">
            ✈
          </span>
          이음길
        </Link>

        {isAuthenticated && (
          <nav className="landing-nav__links" aria-label="주요 메뉴">
            <Link to="/my">개인 페이지</Link>
            <Link to="/group">그룹 페이지</Link>
          </nav>
        )}

        <div className="landing-nav__auth">
          {isAuthenticated ? (
            /* 닉네임은 fetchMe() 응답 후에 채워진다. 도착 전에 렌더하면
               "님 환영합니다"가 되므로 닉네임이 있을 때만 문구를 띄운다 —
               토큰이 있는 동안 로그인 버튼으로 되돌아가지는 않게 한다. */
            currentUser?.nickname && (
              <span className="landing-nav__welcome">
                {currentUser.nickname}님 환영합니다
              </span>
            )
          ) : (
            <>
              {/* <Link to="/login" className="landing-nav__login">
                로그인
              </Link> */}
              <Link to="/login" className="landing-nav__signup">
                로그인
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
