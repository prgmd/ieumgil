// shared/stores/AuthApi.js
// 전역 axiosInstance를 사용한다 — baseURL·타임아웃·Authorization 헤더 자동 삽입,
// 401 시 accessToken 재발급 후 재시도까지 인터셉터가 처리해준다.

import axiosInstance from "../../../../global/api/axiosInstance";

/**
 * 컴포넌트의 분기 로직이 기대하는 에러 형태({ code: 'XXX' })를 유지한다.
 * axios는 실패를 AxiosError로 감싸므로 백엔드 응답 본문을 꺼내 그대로 던진다.
 * 네트워크 오류처럼 응답 자체가 없으면 AxiosError를 그대로 올린다.
 */
function unwrapError(error) {
  throw error.response?.data ?? error;
}

export async function login(provider) {
  // POST /auth/login 에 provider 정보를 담아 보냄
  try {
    const { data } = await axiosInstance.post("/auth/login", { provider });
    return data;
  } catch (error) {
    unwrapError(error);
  }
}

export async function logout() {
  // POST /auth/logout 호출
  try {
    const { data } = await axiosInstance.post("/auth/logout");
    return data;
  } catch (error) {
    unwrapError(error);
  }
}
