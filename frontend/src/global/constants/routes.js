// 앱 전역 라우트 경로 — navigate()/<Link to> 에 하드코딩된 문자열을 여기 한 곳으로 모은다.
// 경로가 바뀌면 여기만 고치면 되고, 오타로 어긋날 일이 없다.
// (라우트 트리 정의 자체는 app/router.jsx 가 소유한다 — 여기는 이동용 절대경로.)
export const ROUTES = {
  landing: "/",
  login: "/login",
  kakaoCallback: "/oauth/kakao/callback",
  my: "/my",
  group: (groupId) => `/groups/${groupId}`,
  project: (groupId, projectId) => `/groups/${groupId}/projects/${projectId}`,
};
