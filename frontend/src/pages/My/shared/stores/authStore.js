import { create } from 'zustand';
import { mockLogin, mockLogout } from './mockAuthApi';

/**
 * 인증 상태. 그룹/프로젝트 스토어는 이 스토어의 currentUser.id를 참조해서
 * role(방장/멤버) 판정을 "파생값"으로 계산한다 — role을 여기 저장하지 않는다.
 * 이유: 방장 위임(GRP-09) 같은 이벤트가 일어나도 group.ownerId만 바뀌면
 * 모든 화면이 자동으로 맞게 반영되도록 하기 위함.
 */
export const useAuthStore = create((set) => ({
  currentUser: {"id" : 1 , nickname : "dd", provider : "kakao", profileImg : "//"}, // { id, nickname, provider, profileImg }
  status: 'idle', // idle | loading | authenticated | error
  error: null,

  login: async (provider) => {
    set({ status: 'loading', error: null });
    try {
      const user = await mockLogin(provider)
      set({ currentUser: user, status: 'authenticated' });
      return user;
    } catch (e) {
      set({ status: 'error', error: e });
      throw e;
    }
  },

  logout: async () => {
    await mockLogout();
    set({ currentUser: null, status: 'idle' });
  },
}));
