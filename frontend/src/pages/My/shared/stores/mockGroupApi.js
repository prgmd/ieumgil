// 목업 API. 에러는 { code: 'XXX' } 형태로 던진다 — 실제 백엔드가 같은 형태의
// 에러 코드를 준다면(예: 스프링 예외를 code 필드로 매핑) 컴포넌트의 분기 로직을
// 손대지 않고 이 파일만 fetch로 교체하면 된다.

const delay = (ms) => new Promise((res) => setTimeout(res, ms));

// ID는 ERD의 BIGINT IDENTITY를 흉내내 숫자로 둔다 (라우트 파라미터는 문자열로
// 들어오므로 소비하는 쪽에서 Number()로 변환해 넘긴다).
// 방장/owner 개념 없음(flat 모델) — 그룹에 ownerId가 없고 멤버에 role이 없다.
let _groups = [
  {
    id: 1,
    name: 'A107 친구들',
    inviteCode: 'YJ3K7Q2M',
    inviteExpiresAt: futureISO(7),
    createdAt: '2026-05-01T00:00:00Z',
    deletedAt: null,
    members: [
      { userId: 1, nickname: '동혁', avatarColor: '#8a5aa8', online: true, joinedAt: '2026-05-01T00:00:00Z' },
      { userId: 2, nickname: '지수', avatarColor: '#c76b6b', online: true, joinedAt: '2026-05-02T00:00:00Z' },
      { userId: 3, nickname: '민준', avatarColor: '#3e8e63', online: true, joinedAt: '2026-05-03T00:00:00Z' },
      { userId: 4, nickname: '수민', avatarColor: '#6b7fc7', online: false, joinedAt: '2026-05-04T00:00:00Z' },
    ],
  },
  {
    id: 2,
    name: '고등학교 동창',
    inviteCode: 'EXPIRED1',
    inviteExpiresAt: pastISO(1), // CODE_EXPIRED 시연용
    createdAt: '2026-01-01T00:00:00Z',
    deletedAt: null,
    members: [
      { userId: 1, nickname: '동혁', avatarColor: '#8a5aa8', online: true, joinedAt: '2026-01-02T00:00:00Z' },
      { userId: 9, nickname: '재현', avatarColor: '#3e8e63', online: false, joinedAt: '2026-01-01T00:00:00Z' },
    ],
  },
  {
    id: 3,
    name: '정원 꽉 찬 그룹',
    inviteCode: 'FULL0009',
    inviteExpiresAt: futureISO(7), // GROUP_FULL 시연용 — 10명 꽉 참
    createdAt: '2026-02-01T00:00:00Z',
    deletedAt: null,
    members: Array.from({ length: 10 }, (_, i) => ({
      userId: 100 + i,
      nickname: `멤버${i + 1}`,
      avatarColor: '#7a6a5c',
      online: false,
      joinedAt: '2026-02-01T00:00:00Z',
    })),
  },
];

let _nextGroupId = 4;
let _nextProjectId = 9;

// PROJECT 초기 데이터 — 컬럼 구성은 ERD.md의 PROJECT 엔티티,
// 필드명은 my-group-api.md의 `GET /api/groups/{groupId}/projects` 응답을 그대로 따른다.
// (transportPref: CAR | PUBLIC, budgetHeadcount·targetBudget은 총액 기준, themeColor는 카드 썸네일 키)
// 세 그룹 모두 PLANNING · DONE을 섞어 두어 카드 상태 분기를 바로 확인할 수 있다.
let _projects = [
  // ── g1 'A107 친구들' ──
  {
    id: 1, groupId: 1, name: '부산 3박 4일',
    destination: '부산', startDate: '2026-10-03', endDate: '2026-10-06',
    transportPref: 'PUBLIC', budgetHeadcount: 4, targetBudget: 600000,
    keywords: ['해변', '야경', '맛집'],
    status: 'PLANNING', doneAt: null, themeColor: 'ocean',
    createdAt: '2026-07-02T09:10:00Z', deletedAt: null,
  },
  {
    id: 2, groupId: 1, name: '가을 전주 미식 여행',
    destination: '전주', startDate: '2026-09-19', endDate: '2026-09-20',
    transportPref: 'CAR', budgetHeadcount: 4, targetBudget: 240000,
    keywords: ['한옥마을', '먹방'],
    status: 'PLANNING', doneAt: null, themeColor: 'autumn',
    createdAt: '2026-07-18T04:25:00Z', deletedAt: null,
  },
  {
    id: 3, groupId: 1, name: '작년 제주 여행',
    destination: '제주', startDate: '2025-08-01', endDate: '2025-08-04',
    transportPref: 'CAR', budgetHeadcount: 4, targetBudget: 800000,
    keywords: ['오름', '카페', '드라이브'],
    status: 'DONE', doneAt: '2025-08-05T00:00:00Z', themeColor: 'sunset',
    createdAt: '2025-07-05T02:00:00Z', deletedAt: null,
  },
  {
    id: 4, groupId: 1, name: '겨울 강릉 당일치기',
    destination: '강릉', startDate: '2026-01-13', endDate: '2026-01-13',
    transportPref: 'PUBLIC', budgetHeadcount: 3, targetBudget: 150000,
    keywords: ['바다', '커피거리'],
    status: 'DONE', doneAt: '2026-01-14T11:30:00Z', themeColor: 'snow',
    createdAt: '2025-12-28T07:40:00Z', deletedAt: null,
  },

  // ── g2 '고등학교 동창' ──
  {
    id: 5, groupId: 2, name: '동창회 여수 밤바다',
    destination: '여수', startDate: '2026-11-07', endDate: '2026-11-08',
    transportPref: 'CAR', budgetHeadcount: 6, targetBudget: 480000,
    keywords: ['야경', '케이블카', '해산물'],
    status: 'PLANNING', doneAt: null, themeColor: 'night',
    createdAt: '2026-06-11T12:00:00Z', deletedAt: null,
  },
  {
    id: 6, groupId: 2, name: '졸업 10주년 경주',
    destination: '경주', startDate: '2025-11-01', endDate: '2025-11-03',
    transportPref: 'PUBLIC', budgetHeadcount: 8, targetBudget: 720000,
    keywords: ['불국사', '단풍'],
    status: 'DONE', doneAt: '2025-11-04T01:00:00Z', themeColor: 'forest',
    createdAt: '2025-09-20T05:15:00Z', deletedAt: null,
  },

  // ── g3 '정원 꽉 찬 그룹' ──
  {
    id: 7, groupId: 3, name: '가평 여름 워크숍',
    destination: '가평', startDate: '2026-08-21', endDate: '2026-08-23',
    transportPref: 'CAR', budgetHeadcount: 10, targetBudget: 1200000,
    keywords: ['펜션', '수상레저', '바비큐'],
    status: 'PLANNING', doneAt: null, themeColor: 'forest',
    createdAt: '2026-07-24T02:05:00Z', deletedAt: null,
  },
  {
    id: 8, groupId: 3, name: '봄 벚꽃 진해',
    destination: '진해', startDate: '2026-03-28', endDate: '2026-03-29',
    transportPref: 'PUBLIC', budgetHeadcount: 10, targetBudget: 500000,
    keywords: ['벚꽃', '군항제'],
    status: 'DONE', doneAt: '2026-03-30T08:00:00Z', themeColor: 'blossom',
    createdAt: '2026-03-02T06:30:00Z', deletedAt: null,
  },
];

// PROJECT.theme_color — 카드 썸네일 그라데이션 키. 생성 시 순환 배정.
const THEME_COLORS = ['ocean', 'sunset', 'forest', 'autumn', 'blossom', 'night', 'snow'];

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
  const mine = _projects.filter((p) => p.groupId === g.id && !p.deletedAt);
  // tripCount: 완료 여부와 무관한 전체 프로젝트 수 (GET /api/groups 응답 필드)
  return { ...g, tripCount: mine.length, doneTrips: mine.filter((p) => p.status === 'DONE').length };
}

// ── 그룹 CRUD (MY-02~04) ─────────────────────────────
export async function createGroup(name, creator) {
  await delay(300);
  if (!name || name.length < 2 || name.length > 20) throw { code: 'INVALID_NAME' };
  const group = {
    id: _nextGroupId++,
    name,
    inviteCode: randomCode(),
    inviteExpiresAt: futureISO(7),
    createdAt: new Date().toISOString(),
    deletedAt: null,
    // 생성자는 첫 멤버일 뿐 특별한 권한이 없다(flat 모델)
    members: [{ userId: creator.id, nickname: creator.nickname, avatarColor: '#9c4a2f', online: true, joinedAt: new Date().toISOString() }],
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
  g.members.push({ userId: user.id, nickname: user.nickname, avatarColor: '#5f9c82', online: true, joinedAt: new Date().toISOString() });
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

// ── 멤버 (GRP-09) ─────────────────────────────────────
// flat 모델이라 강제 방출(kick)은 없고 본인 탈퇴만 가능하다.
export async function leaveGroup(groupId, userId) {
  await delay(300);
  const g = _groups.find((x) => x.id === groupId);
  if (!g) throw { code: 'GROUP_NOT_FOUND' };
  g.members = g.members.filter((m) => m.userId !== userId);

  // 마지막 1인이 나가면 복구할 멤버가 없어 하드 삭제 — 프로젝트도 CASCADE
  if (g.members.length === 0) {
    _groups = _groups.filter((x) => x.id !== groupId);
    _projects = _projects.filter((p) => p.groupId !== groupId);
    return { groupDeleted: true };
  }
  return { groupDeleted: false };
}

// ── 프로젝트 (GRP-02~05) ──────────────────────────────
export async function fetchProjects(groupId) {
  await delay(250);
  // 오래된 카드가 아래로 오도록 생성 역순 정렬 (소프트 삭제분 제외)
  return _projects
    .filter((p) => p.groupId === groupId && !p.deletedAt)
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
}

export async function createProject(groupId, input) {
  await delay(300);
  if (!input.name?.trim()) throw { code: 'NAME_REQUIRED' };
  if (!input.startDate || !input.endDate) throw { code: 'DATE_REQUIRED' };
  if (input.endDate < input.startDate) throw { code: 'VALIDATION_ERROR' };
  const project = {
    id: _nextProjectId++,
    groupId,
    name: input.name.trim(),
    destination: input.destination || '',
    startDate: input.startDate,
    endDate: input.endDate,
    transportPref: input.transportPref || null,
    budgetHeadcount: Math.max(1, Number(input.budgetHeadcount) || 1),
    targetBudget: input.targetBudget ? Number(input.targetBudget) : null,
    keywords: null, // 챗봇이 채우기 전까지 null
    status: 'PLANNING',
    doneAt: null,
    themeColor: THEME_COLORS[_projects.length % THEME_COLORS.length],
    createdAt: new Date().toISOString(),
    deletedAt: null,
  };
  _projects.unshift(project);
  return project;
}

// flat 모델 — 완료 프로젝트를 포함해 모든 멤버가 삭제할 수 있다.
export async function deleteProject(projectId) {
  await delay(250);
  const p = _projects.find((x) => x.id === projectId);
  if (!p) throw { code: 'PROJECT_NOT_FOUND' };
  _projects = _projects.filter((x) => x.id !== projectId);
  return true;
}
