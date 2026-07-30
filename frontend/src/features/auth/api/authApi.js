import axiosInstance from "../../../global/api/axiosInstance";

/**
 * 호출부가 기대하는 에러 형태({ code, message })를 유지한다.
 * axios 는 실패를 AxiosError 로 감싸서 message 가 "Request failed with status code 401"
 * 같은 영문이 되므로, 백엔드 응답 본문을 꺼내 그대로 던진다 — 그래야 화면이 code 로
 * 분기하고 서버가 준 한국어 문구를 그대로 보여줄 수 있다. (groupApi 와 같은 방식)
 */
function unwrapError(error) {
  throw error.response?.data ?? error;
}

/**
 * 카카오 인가 코드를 백엔드로 전달해 로그인/회원가입을 처리한다.
 * - refreshToken 은 응답 body 가 아니라 httpOnly 쿠키(Set-Cookie)로 내려온다.
 * @param {string} code 카카오에서 redirect 로 전달받은 인가 코드
 * @returns {Promise<{ accessToken: string }>}
 */
export const loginWithKakao = async (code) => {
  try {
    const { data } = await axiosInstance.post("/auth/login/kakao", { code });
    // 백엔드 CustomResponse( { result: {...} } ) 래핑 대응
    return data?.result ?? data;
  } catch (error) {
    unwrapError(error);
  }
};


export async function logout() {
  // POST /auth/logout 호출
  // 실패해도 프론트는 토큰을 지우고 로그아웃 처리해야 하므로 여기서 삼킨다.
  try {
    const { data } = await axiosInstance.post("/auth/logout");
    return data;
  } catch (error) {
    console.error("로그아웃 실패:", error);
  }
}

/**
 * 로그인한 사용자의 정보를 조회한다.
 * 사용자 식별은 서버가 accessToken 으로 하므로 파라미터가 없다.
 * @returns {Promise<{ id: number, nickname: string, provider: string, profileImg: string }>}
 */
export const getMe = async () => {
  try {
    const { data } = await axiosInstance.get("/members/me");

    // 백엔드 CustomResponse( { result: {...} } ) 래핑 대응
    return data?.result ?? data;
  } catch (error) {
    unwrapError(error);
  }
};