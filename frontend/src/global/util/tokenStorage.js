/**
 * 토큰 저장소
 * - accessToken 만 프론트(localStorage)에서 관리한다.
 * - refreshToken 은 백엔드가 httpOnly 쿠키로 내려주므로 JS 에서 다루지 않는다.
 *   (브라우저가 요청 시 자동으로 쿠키를 함께 전송한다.)
 * - 저장 키/방식을 여기서만 다루도록 해서, 바뀌더라도 이 파일만 수정하면 되게 한다.
 */

const ACCESS_TOKEN_KEY = "accessToken";

export const tokenStorage = {
  getAccessToken: () => localStorage.getItem(ACCESS_TOKEN_KEY),

  setAccessToken: (accessToken) => {
    if (accessToken) localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  },

  /** 로그아웃 / 토큰 만료 시 삭제 (refreshToken 쿠키는 백엔드 로그아웃 API 로 만료시킨다) */
  clear: () => {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
  },
};
