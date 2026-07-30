import { create } from "zustand";
import { getMe, loginWithKakao, logout } from "../../features/auth/api/authApi";
import { tokenStorage } from "../util/tokenStorage";

/**
 * 인증 상태. 그룹은 flat 모델(방장 없음)이라 권한 판정 자체가 없고,
 * 그룹/프로젝트 조회 훅은 소속 여부 확인에만 currentUser.id를 참조한다.
 *
 * currentUser 는 어느 페이지에서나 같은 한 명이므로 전역 스토어에 둔다.
 * (반대로 그룹·프로젝트처럼 라우트가 바뀌면 의미가 달라지는 데이터는
 *  전역에 두지 않고 각 feature 의 hooks 디렉토리에 있는 훅이 소유한다.)
 *
 * 스토어는 라우트 이동으로 초기화되지 않는다 — 메모리가 비는 건 full page load
 * (새로고침·딥링크·탭 새로 열기) 뿐이고, 그때 App 이 fetchMe() 로 다시 채운다.
 */

// TODO(auth): 백엔드 GET /member/me 연동을 확인한 뒤 이 목업을 지우고 초기값을 null 로 바꾼다.
//             (목업이 남아 있는 동안은 비로그인 상태에서도 이 사용자로 동작한다.)
const MOCK_USER = { id: 1, nickname: "dd", provider: "kakao", profileImg: "//" };

/**
 * 진행 중인 fetchMe 요청.
 *
 * 중복 요청을 막는 것 외에, 나중에 호출한 쪽도 **같은 요청의 완료를 기다리게** 하는 게
 * 목적이다. 예전처럼 즉시 null 을 돌려주면 호출부가 부트스트랩이 끝난 줄로 오해한다 —
 * App 이 이 promise 로 첫 렌더를 게이트하므로, StrictMode 의 effect 2회 실행에서
 * 두 번째 호출이 즉시 resolve 되면 currentUser 가 비어 있는 채로 자식이 마운트된다.
 */
let inFlightFetchMe = null;

export const useAuthStore = create((set) => ({
  currentUser: MOCK_USER,
  status: "idle", // idle | loading | authenticated | error
  error: null,

  /**
   * 내 정보를 받아 스토어를 채운다.
   * 호출 시점은 두 곳뿐이다 — 앱 시작(App 마운트) 1회, 로그인 직후 1회.
   * 페이지 이동마다 호출하지 않는다: 스토어에 이미 있으면 그대로 쓰면 되고,
   * 매번 호출하면 일시적 네트워크 오류가 로그아웃처럼 보이게 된다.
   */
  fetchMe: async () => {
    // 토큰이 없으면 비로그인 — 요청 자체를 보내지 않는다.
    if (!tokenStorage.getAccessToken()) {
      set({ status: "idle" });
      return null;
    }

    // StrictMode 의 effect 2회 실행이나 동시 호출은 요청을 중복 보내지 않고
    // 진행 중인 요청의 완료를 함께 기다린다.
    if (inFlightFetchMe) return inFlightFetchMe;

    set({ status: "loading", error: null });

    inFlightFetchMe = (async () => {
      try {
        const user = await getMe();
        set({ currentUser: user, status: "authenticated" });
        return user;
      } catch (e) {
        // 진짜 인증 실패(401 → 재발급까지 실패)는 axiosInstance 인터셉터가
        // 토큰 정리와 "/" 리다이렉트까지 처리한다. 여기로 오는 건 대개
        // 네트워크 오류·5xx 이므로 토큰을 지우지 않는다 — 일시적 오류로
        // 로그아웃시키면 안 되기 때문.
        set({ status: "error", error: e });
        return null;
      } finally {
        inFlightFetchMe = null;
      }
    })();

    return inFlightFetchMe;
  },

  /**
   * 카카오 인가 코드로 로그인한다.
   * accessToken 저장 → currentUser 채우기까지 한 흐름으로 처리해서,
   * 호출부(KakaoCallback)는 성공 후 이동만 담당한다.
   *
   * fetchMe() 를 부르지 않는다 — 로그인 응답이 GET /members/me 와 같은 정보를
   * 이미 담고 있어 왕복이 하나 낭비된다. (authApi.loginWithKakao 가 필드명을
   * /members/me 와 같은 모양으로 맞춰서 돌려준다.)
   *
   * 주의: 나중에 /members/me 에 필드가 추가되면 그 필드는 "로그인 직후"에는 비어
   * 있고 새로고침 후에만 채워진다. 재현이 까다로운 버그가 되니, 그때는 로그인 응답에도
   * 같은 필드를 추가하거나 여기서 fetchMe() 를 다시 부르는 편이 낫다.
   *
   * @param {string} code 카카오 redirect 로 전달받은 인가 코드
   */
  login: async (code) => {
    const { accessToken, user } = await loginWithKakao(code);
    tokenStorage.setAccessToken(accessToken);
    set({ currentUser: user, status: "authenticated", error: null });
    return user;
  },

  logout: async () => {
    await logout();
    // 서버가 refreshToken 쿠키를 만료시키고, 프론트는 accessToken 을 지운다.
    // (이걸 빼면 ProtectedRoute·useAuth 가 계속 로그인 상태로 판단한다.)
    tokenStorage.clear();
    set({ currentUser: null, status: "idle", error: null });
  },
}));
