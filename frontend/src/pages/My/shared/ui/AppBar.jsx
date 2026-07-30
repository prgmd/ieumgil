import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../../../global/stores/authStore';
import logo from '../../../../assets/img/logo.png';

/**
 * 라우트 공통 상단바.
 * - 좌측: 로고(누르면 개인 페이지로)
 * - 중앙: 현재 위치 — 여러 단계면 마지막 항목만 볼드, 그 앞은 클릭해서 이전 페이지로 이동
 * - 우측: 로그인한 사용자 프로필(카카오 프로필 이미지 + 닉네임) + 로그아웃
 *
 * @param {{label: string, to?: string}[]} crumbs
 *   마지막 항목은 to를 넘기지 않는다(현재 페이지이므로 이동 불가) — 나머지는 to 필수.
 */
export function AppBar({ crumbs }) {
  const navigate = useNavigate();
  const currentUser = useAuthStore((s) => s.currentUser);
  const logout = useAuthStore((s) => s.logout);
  const [loggingOut, setLoggingOut] = useState(false);

  // authStore.logout()은 내부에서 /auth/logout 호출 실패도 삼키고 항상
  // 토큰 삭제 + 상태 초기화까지 마친다 — 그래도 방어적으로 try/finally로
  // 감싸서, 혹시 store 쪽 로직이 바뀌어도 이동은 항상 보장되게 한다.
  //
  // 로그아웃 후 navigate('/')가 필요한 이유: ProtectedRoute는 zustand 상태를
  // 구독하지 않고 렌더 시점에 tokenStorage만 직접 읽는다. 즉 로그아웃으로
  // currentUser가 비워져도 지금 라우트에 그대로 머물러 있으면 화면이 저절로
  // 로그인 페이지로 안 바뀐다 — 그래서 명시적으로 이동시켜준다.
  async function handleLogout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      navigate('/', { replace: true });
    }
  }

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
        <button type="button" className="logout-btn" onClick={handleLogout} disabled={loggingOut}>
          {loggingOut ? '로그아웃 중…' : '로그아웃'}
        </button>
      </div>
    </header>
  );
}
