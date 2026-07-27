import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../global/hooks/useAuth';

export function LandingNav() {
  const [isScrolled, setIsScrolled] = useState(false);
  const { isAuthenticated } = useAuth();

  // TODO: "내 정보 API" 연동 시 실제 닉네임으로 교체
  const name = '';

  useEffect(() => {
    const onScroll = () => setIsScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  return (
    <header className={`landing-nav ${isScrolled ? 'is-scrolled' : ''}`}>
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
            <span className="landing-nav__welcome">{name}님 환영합니다</span>
          ) : (
            <>
              <Link to="/login" className="landing-nav__login">
                로그인
              </Link>
              <Link to="/login" className="landing-nav__signup">
                가입하기
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
