import { Link } from 'react-router-dom';

const HERO_ICONS = [
  { emoji: '🖼️', label: '여행 기록' },
  { emoji: '🔖', label: '일정 저장' },
  { emoji: '💼', label: '그룹 준비물' },
  { emoji: '💰', label: '예산 관리' },
];

export function HeroSection() {
  return (
    <section className="hero">
      <div className="hero__deco" aria-hidden="true">
        <svg viewBox="0 0 1200 700" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path
            className="hero__deco-arc hero__deco-arc--a"
            d="M-40 120 C 160 -40, 420 -20, 480 160"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeDasharray="2 10"
            strokeLinecap="round"
          />
          <path
            className="hero__deco-arc hero__deco-arc--b"
            d="M760 40 C 980 60, 1160 220, 1120 420"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeDasharray="2 10"
            strokeLinecap="round"
          />
          <path
            className="hero__deco-arc hero__deco-arc--c"
            d="M120 560 C 320 660, 620 660, 760 540"
            stroke="currentColor"
            strokeWidth="1.5"
            strokeDasharray="2 10"
            strokeLinecap="round"
          />
        </svg>
      </div>

      <ul className="hero__icons">
        {HERO_ICONS.map((icon) => (
          <li key={icon.label} className="hero__icon" title={icon.label}>
            <span aria-hidden="true">{icon.emoji}</span>
          </li>
        ))}
      </ul>

      <h1 className="hero__title">
        <span>여행을 잇는 길,</span>
        <span className="hero__title-accent">
          이음길
          <svg
            className="hero__underline"
            viewBox="0 0 220 20"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            aria-hidden="true"
          >
            <path
              d="M4 14C40 4 80 4 110 10C140 16 180 16 216 6"
              stroke="var(--color-accent)"
              strokeWidth="6"
              strokeLinecap="round"
            />
          </svg>
        </span>
      </h1>

      <p className="hero__subtitle">
        친구들과 함께 세부 일정을 만들고, AI에게 추천 받으며,
        <br />
        모든 계획을 한 곳에서 완벽하게 관리하세요.
      </p>

      <Link to="/login" className="hero__cta">
        이음길 시작하기
      </Link>
    </section>
  );
}
