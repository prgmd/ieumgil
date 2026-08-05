import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../../../global/stores/authStore';
import { Avatar } from './Avatar';
import logo from '../../../../assets/img/logo.png';

/** 겹쳐 보여줄 멤버 수 상한 — 넘치면 마지막에 "+N" 칩으로 접는다. */
const CREW_LIMIT = 4;

/**
 * 라우트 공통 상단바.
 * - 좌측: 로고(누르면 개인 페이지로)
 * - 중앙: 현재 위치 — 여러 단계면 마지막 항목만 볼드, 그 앞은 클릭해서 이전 페이지로 이동
 * - 우측: (선택) 함께 보는 멤버 아바타 겹침 + 로그인한 사용자 프로필 + 로그아웃
 *
 * @param {{label: string, to?: string}[]} crumbs
 *   마지막 항목은 to를 넘기지 않는다(현재 페이지이므로 이동 불가) — 나머지는 to 필수.
 * @param {{memberId: number, nickname: string, profileImg?: string}[]} [members]
 *   이 화면을 함께 보는 멤버(대시보드=프로젝트 멤버). 넘기지 않으면 아예 렌더하지 않아
 *   개인/그룹 페이지의 상단바 모양은 그대로다.
 * @param {number[]} [activeMemberIds]
 *   지금 활동 중인 멤버 id. 실시간 접속 정보(WebSocket)가 붙으면 그 결과를 그대로
 *   넣어주면 된다 — 로그인 사용자는 이 화면을 보고 있는 당사자라 항상 활동 중으로 본다.
 * @param {import('react').ReactNode} [extra]
 *   경로 오른쪽에 이어 붙는 화면별 요소(대시보드의 제목·기간·모드 전환 등).
 *   넘기지 않으면 아무것도 렌더하지 않아 다른 페이지의 상단바 모양은 그대로다.
 */
export function AppBar({ crumbs, members = [], activeMemberIds = [], extra = null }) {
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
    } catch (e) {
      // authStore.logout()이 내부에서 실패를 삼키는 것이 기본 동작이지만,
      // 혹시 예외가 새어나오더라도 unhandled rejection이 되지 않도록 안전망으로 막는다.
      console.error('로그아웃 처리 중 오류', e);
    } finally {
      navigate('/', { replace: true });
    }
  }

  // 내 프로필은 오른쪽에 이미 있으므로 겹침 아바타에서는 뺀다(같은 얼굴이 두 번 나오지 않게).
  const crew = members.filter((m) => m.memberId !== currentUser?.id);
  const shownCrew = crew.slice(0, CREW_LIMIT);
  const hiddenCrewCount = crew.length - shownCrew.length;
  const activeIds = new Set(activeMemberIds);

  return (
    <header className="appbar">
      <button className="logo" onClick={() => navigate('/my')} aria-label="개인 페이지로 이동">
        <img src={logo} alt="이음길" className="logo-img" />
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

      {extra}

      {/* 함께 보는 멤버 — 활동 중이면 초록 점, 아니면 회색 점 + 아바타를 흐리게 */}
      {crew.length > 0 && (
        <div className="crew" aria-label="프로젝트 멤버">
          {shownCrew.map((m) => {
            const online = activeIds.has(m.memberId);
            return (
              <span
                key={m.memberId}
                className={`crew-item ${online ? 'is-on' : 'is-off'}`}
                title={`${m.nickname} · ${online ? '활동 중' : '오프라인'}`}
              >
                <Avatar
                  memberId={m.memberId}
                  nickname={m.nickname}
                  profileImg={m.profileImg}
                />
                <i className="crew-dot" />
              </span>
            );
          })}
          {hiddenCrewCount > 0 && (
            <span className="crew-more" title={`외 ${hiddenCrewCount}명`}>
              +{hiddenCrewCount}
            </span>
          )}
        </div>
      )}

      <div className={`me ${crew.length > 0 ? 'with-crew' : ''}`}>
        {/* 멤버를 함께 보여주는 화면에서는 내 아바타에도 같은 활동 표시를 붙여
            "겹친 아바타들 + 나"가 한 줄로 읽히게 한다. */}
        <span className={members.length > 0 ? 'crew-item is-on' : undefined}>
          {currentUser?.profileImg?.startsWith('http') ? (
            <img src={currentUser.profileImg} alt="" className="me-avatar" />
          ) : (
            <span className="mini-av me-avatar-fallback">
              {currentUser?.nickname?.[0] ?? '?'}
            </span>
          )}
          {members.length > 0 && <i className="crew-dot" />}
        </span>
        <span>{currentUser?.nickname ?? '게스트'}님</span>
        <button type="button" className="logout-btn" onClick={handleLogout} disabled={loggingOut}>
          {loggingOut ? '로그아웃 중…' : '로그아웃'}
        </button>
      </div>
    </header>
  );
}
