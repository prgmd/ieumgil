/**
 * memberId → 고정 색상(hue). 팔레트를 두지 않고 황금각(137°)으로 색상환을 도는
 * 방식 — 멤버가 몇 명이든 서로 잘 구분되는 색이 결정적으로 나온다(재접속해도 같은 색).
 * 라이브 커서(RemoteCursorLayer)와 Day 탭의 "보는 중" 점이 같은 색을 공유한다.
 */
export const hueOf = (id) => (Number(id) * 137) % 360;
