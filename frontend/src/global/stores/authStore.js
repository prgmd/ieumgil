import { create } from "zustand";
import { loginWithKakao, logout } from "../../features/auth/api/authApi";
/**
 * 인증 상태. 그룹은 flat 모델(방장 없음)이라 권한 판정 자체가 없고,
 * 그룹/프로젝트 스토어는 소속 여부 확인에만 currentUser.id를 참조한다.
 */
export const useAuthStore = create((set) => ({
  currentUser: { id: 1, nickname: "dd", provider: "kakao", profileImg: "//" }, // { id, nickname, provider, profileImg }
  status: "idle", // idle | loading | authenticated | error
  error: null,

  login: async (provider) => {
    set({ status: "loading", error: null });
    try {
      const user = await loginWithKakao();
      set({ currentUser: user, status: "authenticated" });
      return user;
    } catch (e) {
      set({ status: "error", error: e });
      throw e;
    }
  },

  logout: async () => {
    await logout()
    set({ currentUser: null, status: "idle" });
  },
}));
