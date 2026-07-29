import { tokenStorage } from "../util/tokenStorage";

/**
 * 로그인 여부를 판단하는 훅.
 * - 현재는 accessToken 존재만으로 로그인 상태를 판단한다.
 * - 사용자 정보(닉네임 등)는 추후 "내 정보 API" 연동 시 여기에 추가한다.
 */
export function useAuth() {
  const isAuthenticated = !!tokenStorage.getAccessToken();

  return { isAuthenticated };
}
