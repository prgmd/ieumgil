// 서버는 멤버 아바타 색을 주지 않는다(profileImg만 내려온다) — 이니셜 배경색은
// memberId로 고정 배정해 같은 사람이 항상 같은 색으로 보이게 한다.
const AVATAR_COLORS = ['#8a5aa8', '#c76b6b', '#3e8e63', '#6b7fc7', '#9c4a2f', '#5f9c82'];

export function avatarColor(memberId) {
  return AVATAR_COLORS[(memberId ?? 0) % AVATAR_COLORS.length];
}

/**
 * 멤버 아바타 — profileImg가 유효한 URL이면 사진, 아니면 닉네임 첫 글자를
 * 고정 색상 원으로 보여준다. `.mini-av` 클래스를 그대로 써서 카드/패널의
 * 겹침 레이아웃(margin-left, border 등) CSS를 photo/initial 양쪽 다 그대로 탄다.
 */
export function Avatar({ memberId, nickname, profileImg, className = '' }) {
  const hasPhoto = typeof profileImg === 'string' && profileImg.startsWith('http');

  if (hasPhoto) {
    return <img src={profileImg} alt={nickname ?? ''} className={`mini-av ${className}`} />;
  }

  return (
    <span className={`mini-av ${className}`} style={{ background: avatarColor(memberId) }}>
      {nickname?.[0] ?? '?'}
    </span>
  );
}
