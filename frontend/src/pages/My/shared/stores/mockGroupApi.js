// 목업 API. 에러는 { code: 'XXX' } 형태로 던진다 — 실제 백엔드가 같은 형태의
// 에러 코드를 준다면(예: 스프링 예외를 code 필드로 매핑) 컴포넌트의 분기 로직을
// 손대지 않고 이 파일만 fetch로 교체하면 된다.

const delay = (ms) => new Promise((res) => setTimeout(res, ms));

let _groups = [
  {
    id: 'g1',
    name: 'A107 친구들',
    ownerId: 'u1',
    inviteCode: 'YJ3K7Q2M',
    inviteExpiresAt: futureISO(7),
    createdAt: '2026-05-01T00:00:00Z',
    deletedAt: null,
    members: [
      { userId: 'u1', nickname: '동혁', avatarColor: '#8a5aa8', role: 'ADMIN', online: true, joinedAt: '2026-05-01T00:00:00Z' },
      { userId: 'u2', nickname: '지수', avatarColor: '#c76b6b', role: 'MEMBER', online: true, joinedAt: '2026-05-02T00:00:00Z' },
      { userId: 'u3', nickname: '민준', avatarColor: '#3e8e63', role: 'MEMBER', online: true, joinedAt: '2026-05-03T00:00:00Z' },
      { userId: 'u4', nickname: '수민', avatarColor: '#6b7fc7', role: 'MEMBER', online: false, joinedAt: '2026-05-04T00:00:00Z' },
    ],
  },
  {
    id: 'g2',
    name: '고등학교 동창',
    ownerId: 'u9',
    inviteCode: 'EXPIRED1',
    inviteExpiresAt: pastISO(1), // CODE_EXPIRED 시연용
    createdAt: '2026-01-01T00:00:00Z',
    deletedAt: null,
    members: [
      { userId: 'u9', nickname: '재현', avatarColor: '#3e8e63', role: 'ADMIN', online: false, joinedAt: '2026-01-01T00:00:00Z' },
    ],
  },
  {
    id: 'g3',
    name: '정원 꽉 찬 그룹',
    ownerId: 'u10',
    inviteCode: 'FULL0009',
    inviteExpiresAt: futureISO(7), // GROUP_FULL 시연용 — 10명 꽉 참
    createdAt: '2026-02-01T00:00:00Z',
    deletedAt: null,
    members: Array.from({ length: 10 }, (_, i) => ({
      userId: `f${i}`,
      nickname: `멤버${i + 1}`,
      avatarColor: '#7a6a5c',
      role: i === 0 ? 'ADMIN' : 'MEMBER',
      online: false,
      joinedAt: '2026-02-01T00:00:00Z',
    })),
  },
];

let _projects = [
  { id: 'p1', groupId: 'g1', name: '부산 3박 4일', startDate: '2026-10-03', endDate: '2026-10-06', destination: '부산', headcount: 4, transport: 'KTX/기차', status: 'PLANNING', doneAt: null },
  { id: 'p2', groupId: 'g1', name: '작년 제주 여행', startDate: '2025-08-01', endDate: '2025-08-04', destination: '제주', headcount: 4, transport: '비행기', status: 'DONE', doneAt: '2025-08-05T00:00:00Z' },
];

function futureISO(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString();
}

function pastISO(days) {
  const d = new Date();
  d.setDate(d.getDate() - days);
  return d.toISOString();
}

function randomCode() {
  // I·O·0·1 제외 (혼동 방지 — MY-05, GRP-06)
  const chars = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  let c = '';
  for (let i = 0; i < 8; i++) c += chars[Math.floor(Math.random() * chars.length)];
  return c;
}

// ── 조회 ──────────────────────────────────────────
export async function fetchMyGroups(userId) {
  await delay(300);
  return _groups
    .filter((g) => !g.deletedAt && g.members.some((m) => m.userId === userId))
    .map(toGroupSummary);
}

export async function fetchGroup(groupId) {
  await delay(250);
  const g = _groups.find((x) => x.id === groupId && !x.deletedAt);
  if (!g) throw { code: 'GROUP_NOT_FOUND' };
  return toGroupSummary(g);
}

function toGroupSummary(g) {
  const doneTrips = _projects.filter((p) => p.groupId === g.id && p.status === 'DONE').length;
  return { ...g, doneTrips };
}

// ── 그룹 CRUD (MY-02~04) ─────────────────────────────
export async function createGroup(name, ownerId, ownerUser) {
  await delay(300);
  if (!name || name.length < 2 || name.length > 20) throw { code: 'INVALID_NAME' };
  const group = {
    id: 'g' + Date.now(),
    name,
    ownerId,
    inviteCode: randomCode(),
    inviteExpiresAt: futureISO(7),
    createdAt: new Date().toISOString(),
    deletedAt: null,
    members: [{ userId: ownerId, nickname: ownerUser.nickname, avatarColor: '#9c4a2f', role: 'ADMIN', online: true, joinedAt: new Date().toISOString() }],
  };
  _groups.push(group);
  return toGroupSummary(group);
}

export async function renameGroup(groupId, name) {
  await delay(200);
  const g = _groups.find((x) => x.id === groupId);
  if (!g) throw { code: 'GROUP_NOT_FOUND' };
  if (!name || name.length < 2 || name.length > 20) throw { code: 'INVALID_NAME' };
  g.name = name;
  return toGroupSummary(g);
}

export async function deleteGroup(groupId, typedName) {
  await delay(300);
  const g = _groups.find((x) => x.id === groupId);
  if (!g) throw { code: 'GROUP_NOT_FOUND' };
  if (typedName !== g.name) throw { code: 'NAME_MISMATCH' }; // MY-04: 그룹명 직접 입력 확인
  g.deletedAt = new Date().toISOString(); // 소프트 삭제, 30일 후 완전 삭제(운영 배치 영역)
  return true;
}

// ── 초대 코드 (MY-05, GRP-06) ─────────────────────────
export async function joinByCode(code, user) {
  await delay(350);
  if (!/^[A-Z0-9]{8}$/.test(code)) throw { code: 'INVALID_FORMAT' };
  const g = _groups.find((x) => x.inviteCode === code && !x.deletedAt);
  if (!g) throw { code: 'CODE_NOT_FOUND' };
  if (new Date(g.inviteExpiresAt) < new Date()) throw { code: 'CODE_EXPIRED' };
  if (g.members.length >= 10) throw { code: 'GROUP_FULL' };
  if (g.members.some((m) => m.userId === user.id)) throw { code: 'ALREADY_MEMBER' };
  g.members.push({ userId: user.id, nickname: user.nickname, avatarColor: '#5f9c82', role: 'MEMBER', online: true, joinedAt: new Date().toISOString() });
  return toGroupSummary(g);
}

export async function reissueInviteCode(groupId) {
  await delay(250);
  const g = _groups.find((x) => x.id === groupId);
  if (!g) throw { code: 'GROUP_NOT_FOUND' };
  g.inviteCode = randomCode();
  g.inviteExpiresAt = futureISO(7);
  return { inviteCode: g.inviteCode, inviteExpiresAt: g.inviteExpiresAt };
}

// ── 멤버 관리 (GRP-07, GRP-09) ────────────────────────
export async function kickMember(groupId, targetUserId) {
  await delay(250);
  const g = _groups.find((x) => x.id === groupId);
  if (!g) throw { code: 'GROUP_NOT_FOUND' };
  g.members = g.members.filter((m) => m.userId !== targetUserId);
  return toGroupSummary(g);
}

export async function leaveGroup(groupId, userId) {
  await delay(300);
  const g = _groups.find((x) => x.id === groupId);
  if (!g) throw { code: 'GROUP_NOT_FOUND' };
  const wasOwner = g.ownerId === userId;
  g.members = g.members.filter((m) => m.userId !== userId);

  if (g.members.length === 0) {
    g.deletedAt = new Date().toISOString();
    return { softDeleted: true, newOwnerId: null };
  }
  if (wasOwner) {
    // 가입일이 가장 오래된 멤버에게 자동 위임
    const next = [...g.members].sort((a, b) => new Date(a.joinedAt) - new Date(b.joinedAt))[0];
    g.ownerId = next.userId;
    next.role = 'ADMIN';
    return { softDeleted: false, newOwnerId: next.userId };
  }
  return { softDeleted: false, newOwnerId: null };
}

// ── 프로젝트 (GRP-02~05) ──────────────────────────────
export async function fetchProjects(groupId) {
  await delay(250);
  return _projects.filter((p) => p.groupId === groupId);
}

export async function createProject(groupId, input) {
  await delay(300);
  if (!input.name?.trim()) throw { code: 'NAME_REQUIRED' };
  if (!input.startDate || !input.endDate) throw { code: 'DATE_REQUIRED' };
  const project = {
    id: 'p' + Date.now(),
    groupId,
    name: input.name.trim(),
    startDate: input.startDate,
    endDate: input.endDate,
    destination: input.destination || '',
    headcount: Math.max(1, Number(input.headcount) || 1),
    transport: input.transport,
    status: 'PLANNING',
    doneAt: null,
  };
  _projects.unshift(project);
  return project;
}

export async function deleteProject(projectId, requesterRole) {
  await delay(250);
  const p = _projects.find((x) => x.id === projectId);
  if (!p) throw { code: 'PROJECT_NOT_FOUND' };
  if (p.status === 'DONE' && requesterRole !== 'ADMIN') throw { code: 'ADMIN_ONLY' }; // GRP-04
  _projects = _projects.filter((x) => x.id !== projectId);
  return true;
}
