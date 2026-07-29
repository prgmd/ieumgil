import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../../../global/stores/authStore';
import logo from '../../../../assets/img/logo.png';

/**
 * 라우트 공통 상단바.
 * - 좌측: 로고(누르면 개인 페이지로)
 * - 중앙: 현재 위치 — 여러 단계면 마지막 항목만 볼드, 그 앞은 클릭해서 이전 페이지로 이동
 * - 우측: 로그인한 사용자 프로필(카카오 프로필 이미지 + 닉네임)
 *
 * @param {{label: string, to?: string}[]} crumbs
 *   마지막 항목은 to를 넘기지 않는다(현재 페이지이므로 이동 불가) — 나머지는 to 필수.
 */
export function AppBar({ crumbs }) {
  const navigate = useNavigate();
  const currentUser = useAuthStore((s) => s.currentUser);

  return (
    <header className="appbar">
      <button className="logo" onClick={() => navigate('/my')} aria-label="개인 페이지로 이동">
        <img src={logo} alt="아음길" className="logo-img" />
      </button>

      <div className="crumb">
        {crumbs.map((c, i) => (
          <span key={i} className="crumb-item">
            {i > 0 && <span className="crumb-sep">›</span>}
            {c.to ? (
              <button type="button" onClick={() => navigate(c.to)}>
                {c.label}
              </button>
            ) : (
              <b>{c.label}</b>
            )}
          </span>
        ))}
      </div>

      <div className="me">
        {currentUser?.profileImg?.startsWith('http') ? (
          <img src={currentUser.profileImg} alt="" className="me-avatar" />
        ) : (
          <span className="mini-av me-avatar-fallback">
            {currentUser?.nickname?.[0] ?? '?'}
          </span>
        )}
        <span>{currentUser?.nickname ?? '게스트'}님</span>
        {/* TODO(auth): 실제 로그아웃 처리는 다른 팀원이 붙일 예정 — 지금은 UI만 노출 */}
        <button type="button" className="logout-btn">
          로그아웃
        </button>
      </div>
    </header>
  );
}
