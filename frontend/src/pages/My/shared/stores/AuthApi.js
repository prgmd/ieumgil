// shared/stores/authApi.js (새로 생성)

const BASE_URL = "http://localhost:8000/api";

// 공통 fetch 헬퍼 함수
async function fetchClient(endpoint, options = {}) {
  const response = await fetch(`${BASE_URL}${endpoint}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  const data = await response.json();

  if (!response.ok) {
    // 백엔드에서 보내준 { code: 'XXX' } 형태의 에러를 그대로 던져줍니다.
    throw data;
  }
  return data;
}

export async function login(provider) {
  // POST /api/auth/login 에 provider 정보를 담아 보냄
  return await fetchClient("/auth/login", {
    method: "POST",
    body: JSON.stringify({ provider }),
  });
}

export async function logout() {
  // POST /api/auth/logout 호출
  return await fetchClient("/auth/logout", {
    method: "POST",
  });
}
