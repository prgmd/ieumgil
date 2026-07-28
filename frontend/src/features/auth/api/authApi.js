import axiosInstance from "../../../global/api/axiosInstance";

/**
 * 카카오 인가 코드를 백엔드로 전달해 로그인/회원가입을 처리한다.
 * - refreshToken 은 응답 body 가 아니라 httpOnly 쿠키(Set-Cookie)로 내려온다.
 * @param {string} code 카카오에서 redirect 로 전달받은 인가 코드
 * @returns {Promise<{ accessToken: string }>}
 */
export const loginWithKakao = async (code) => {
  const { data } = await axiosInstance.post("/auth/login/kakao", { code });

  // 백엔드 CustomResponse( { result: {...} } ) 래핑 대응
  return data?.result ?? data;
};


export async function logout() {
  // POST /auth/logout 호출
  try {
    const { data } = await axiosInstance.post("/auth/logout");
    return data;
  } catch (error) {
    console.log("로그아웃 에러")
  }
}