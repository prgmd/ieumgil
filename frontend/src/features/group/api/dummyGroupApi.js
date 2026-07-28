// ⚠️ 임시 파일 — 백엔드 그룹 API 가 뜨기 전까지 그룹 페이지를 눈으로 확인하기 위한 더미다.
//
// 지우는 방법 (3곳):
//   1. features/group/hooks/useGroupDetail.js 의 import 를 my/api/groupApi 로 되돌린다
//   2. features/group/hooks/useProjects.js 도 같은 방식으로 되돌린다
//   3. 이 파일을 삭제한다
//
// 함수 이름·인자·반환 형태를 features/my/api/groupApi.js 와 똑같이 맞춰뒀고,
// 반환 payload 는 docs/api/my-group-api.md 의 result 스펙(필드명 memberId·projectId 등)을
// 그대로 따른다 — 그래서 import 만 되돌리면 화면 코드는 손댈 필요가 없다.
//
// 에러는 실제 api 래퍼와 같은 { code } 형태로 던진다(CustomResponse.code).

const delay = (ms) => new Promise((res) => setTimeout(res, ms));

// 이 더미가 존재한다고 가정하는 그룹. 다른 groupId 로 들어오면 NOT_FOUND 를 던져
// 그룹 페이지의 "없는 그룹 → /my 로 되돌리기" 분기도 그대로 동작한다.
const DUMMY_GROUP_ID = 1;

// GET /api/groups/{groupId}/members 의 result + 그룹명.
// (실서버 스펙에는 멤버 응답에 name 이 없다 — 개인 페이지의 GET /api/groups 목록에서
//  받은 name 을 함께 들고 오거나 백엔드에 필드 추가가 필요하다.)
let _group = {
  groupId: DUMMY_GROUP_ID,
  name: "A107 친구들",
  inviteCode: "YJ3K7Q2M",
  inviteExpiresAt: futureISO(7),
  members: [
    { memberId: 1, nickname: "동혁", profileImg: null, online: true },
    { memberId: 2, nickname: "지수", profileImg: null, online: true },
    { memberId: 3, nickname: "민준", profileImg: null, online: false },
    { memberId: 4, nickname: "수민", profileImg: null, online: false },
  ],
};

// GET /api/groups/{groupId}/projects 의 result.
// PLANNING·DONE 을 섞어 두어 카드 상태 분기를 바로 확인할 수 있다.
let _projects = [
  {
    projectId: 1,
    name: "부산 3박 4일",
    startDate: "2026-10-03",
    endDate: "2026-10-06",
    destination: "부산",
    budgetHeadcount: 4,
    transportPref: "PUBLIC",
    status: "PLANNING",
    themeColor: "ocean",
  },
  {
    projectId: 2,
    name: "가을 전주 미식 여행",
    startDate: "2026-09-19",
    endDate: "2026-09-20",
    destination: "전주",
    budgetHeadcount: 4,
    transportPref: "CAR",
    status: "PLANNING",
    themeColor: "autumn",
  },
  {
    projectId: 3,
    name: "작년 제주 여행",
    startDate: "2025-08-01",
    endDate: "2025-08-04",
    destination: "제주",
    budgetHeadcount: 4,
    transportPref: "CAR",
    status: "DONE",
    themeColor: "sunset",
  },
  {
    projectId: 4,
    name: "겨울 강릉 당일치기",
    startDate: "2026-01-13",
    endDate: "2026-01-13",
    destination: "강릉",
    budgetHeadcount: 3,
    transportPref: "PUBLIC",
    status: "DONE",
    themeColor: "snow",
  },
];

let _nextProjectId = 5;

const THEME_COLORS = ["ocean", "sunset", "forest", "autumn", "blossom", "night", "snow"];

function futureISO(days) {
  const d = new Date();
  d.setDate(d.getDate() + days);
  return d.toISOString();
}

function randomCode() {
  // I·O·0·1 제외 (혼동 방지)
  const chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  let c = "";
  for (let i = 0; i < 8; i++) c += chars[Math.floor(Math.random() * chars.length)];
  return c;
}

function assertDummyGroup(groupId) {
  // 실서버라면 비멤버 접근은 403 FORBIDDEN, 없는 그룹은 404 NOT_FOUND 다.
  if (groupId !== DUMMY_GROUP_ID) throw { code: "NOT_FOUND" };
}

// ── 조회 ──────────────────────────────────────────
export async function fetchGroup(groupId) {
  await delay(250);
  assertDummyGroup(groupId);
  return { ..._group, members: [..._group.members] };
}

export async function fetchProjects(groupId) {
  await delay(250);
  assertDummyGroup(groupId);
  return [..._projects];
}

// ── 초대 코드 ─────────────────────────────────────
export async function reissueInviteCode(groupId) {
  await delay(250);
  assertDummyGroup(groupId);
  _group = { ..._group, inviteCode: randomCode(), inviteExpiresAt: futureISO(7) };
  return { inviteCode: _group.inviteCode, inviteExpiresAt: _group.inviteExpiresAt };
}

// ── 멤버 ──────────────────────────────────────────
// flat 모델 — 강제 방출은 없고 본인 탈퇴만 가능하다.
// 나가는 사람은 서버가 토큰으로 판별하므로, 더미에서는 authStore 의 목업 유저(id 1)를 지운다.
const DUMMY_ME_ID = 1;

export async function leaveGroup(groupId) {
  await delay(250);
  assertDummyGroup(groupId);
  _group = {
    ..._group,
    members: _group.members.filter((m) => m.memberId !== DUMMY_ME_ID),
  };
  // 마지막 1인이 나가면 그룹은 하드 삭제된다.
  return { groupDeleted: _group.members.length === 0 };
}

// ── 프로젝트 ──────────────────────────────────────
export async function createProject(groupId, input) {
  await delay(300);
  assertDummyGroup(groupId);
  if (!input.name?.trim()) throw { code: "VALIDATION_ERROR" };
  if (!input.startDate || !input.endDate) throw { code: "VALIDATION_ERROR" };
  if (input.endDate < input.startDate) throw { code: "VALIDATION_ERROR" };

  const project = {
    projectId: _nextProjectId++,
    name: input.name.trim(),
    startDate: input.startDate,
    endDate: input.endDate,
    destination: input.destination || "",
    budgetHeadcount: Math.max(1, Number(input.budgetHeadcount) || 1),
    transportPref: input.transportPref || null,
    status: "PLANNING",
    themeColor: THEME_COLORS[_projects.length % THEME_COLORS.length],
  };
  _projects = [project, ..._projects];

  // 실서버 POST 응답의 result 는 { projectId } 하나뿐이다 — 그 형태를 그대로 돌려준다.
  // (카드에 필요한 나머지 필드는 호출한 훅이 입력 폼과 합쳐 만든다.)
  return { projectId: project.projectId };
}

export async function deleteProject(projectId) {
  await delay(250);
  const exists = _projects.some((p) => p.projectId === projectId);
  if (!exists) throw { code: "NOT_FOUND" };
  _projects = _projects.filter((p) => p.projectId !== projectId);
  return null;
}
