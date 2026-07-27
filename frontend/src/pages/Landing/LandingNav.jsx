import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

export function LandingNav() {
  const [isScrolled, setIsScrolled] = useState(false);

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

        <nav className="landing-nav__links" aria-label="주요 메뉴">
          <Link to="/my">개인 페이지</Link>
          <Link to="/group">그룹 페이지</Link>
        </nav>

        <div className="landing-nav__auth">
          <Link to="/auth" className="landing-nav__login">
            로그인
          </Link>
          <Link to="/auth?mode=signup" className="landing-nav__signup">
            가입하기
          </Link>
        </div>
      </div>
    </header>
  );
}
