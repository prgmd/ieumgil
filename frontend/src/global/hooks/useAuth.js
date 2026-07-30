import { useAuthStore } from "../stores/authStore";
import { tokenStorage } from "../util/tokenStorage";

/**
 * 로그인 여부와 현재 사용자를 함께 돌려주는 훅.
 * - isAuthenticated 는 accessToken 존재만으로 판단한다.
 * - currentUser 는 { id, nickname, profileImg } 모양이고, 로그인 직후에는 login() 이,
 *   새로고침·딥링크로 들어왔을 때는 App 의 fetchMe() 가 채운다. 후자는 응답이
 *   도착하기 전에는 비어 있으므로, 닉네임을 쓰는 쪽은 그 공백을 다뤄야 한다.
 */
export function useAuth() {
  const isAuthenticated = !!tokenStorage.getAccessToken();
  const currentUser = useAuthStore((s) => s.currentUser);

  return { isAuthenticated, currentUser };
}
