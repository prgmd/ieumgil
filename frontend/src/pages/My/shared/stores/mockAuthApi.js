// 실제 백엔드 붙기 전까지의 목업. 나중에 fetch('/api/auth/...')로 바꿔도
// 이 파일의 함수 시그니처(입력/출력/에러 형태)만 유지하면 스토어·컴포넌트는 그대로 둘 수 있다.

const delay = (ms) => new Promise((res) => setTimeout(res, ms));

const MOCK_USER = {
  id: 'u1',
  nickname: '동혁',
  provider: 'kakao',
  profileImg: null,
};

export async function mockLogin(provider) {
  await delay(300);
  // AUTH-02/03: provider별 별도 계정 취급. 여기선 데모로 고정 유저 반환.
  return { ...MOCK_USER, provider };
}

export async function mockLogout() {
  await delay(150);
  return true;
}
