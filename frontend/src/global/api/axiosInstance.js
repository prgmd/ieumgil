import axios from "axios";
import { tokenStorage } from "../util/tokenStorage";
import { getClientId } from "./clientId";

/**
 * 전역 Axios 인스턴스
 * - baseURL / timeout / 기본 헤더 등 공통 설정을 한곳에서 관리한다.
 * - 앱 전역에서는 이 인스턴스(axiosInstance)를 import 해서 사용한다.
 */

export const BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api";

// 백엔드에서 토큰 재발급 처리하는 엔드포인트 (백엔드 스펙 확정 시 이 값만 수정)
const REISSUE_URL = "/auth/refresh";

// 리프레시 자체가 실패했을 때 이동시킬 로그인 경로
const LOGIN_PATH = "/";

const axiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 10000,
  headers: {
    "Content-Type": "application/json",
  },
  withCredentials: true, // refreshToken(httpOnly 쿠키)을 요청에 자동 포함시키기 위해 필수
});

/* ------------------------------------------------------------------ *
 * 요청 인터셉터: accessToken 을 Authorization 헤더에 자동 삽입
 * + 변경 요청에는 X-Client-Id(탭 UUID) 첨부
 * ------------------------------------------------------------------ */

// 브로드캐스트에서 자기 op 를 스킵하기 위한 헤더(dashboard-api.md 공통 규약).
// 대시보드 외 엔드포인트는 이 헤더를 무시하므로 변경 메서드 전체에 일괄 첨부한다 —
// 빠뜨리면 에러 없이 자기 변경이 이중 적용되는, 원인 찾기 어려운 종류의 버그가 된다.
const MUTATING_METHODS = new Set(["post", "put", "patch", "delete"]);

axiosInstance.interceptors.request.use(
  (config) => {
    const accessToken = tokenStorage.getAccessToken();
    if (accessToken) {
      config.headers.Authorization = `Bearer ${accessToken}`;
    }
    if (MUTATING_METHODS.has(config.method)) {
      config.headers["X-Client-Id"] = getClientId();
    }
    return config;
  },
  (error) => Promise.reject(error),
);

/* ------------------------------------------------------------------ *
 * 응답 인터셉터: 401 발생 시 accessToken 재발급 후 원래 요청 재시도
 * ------------------------------------------------------------------ */

// 동시에 여러 요청이 401 을 받아도 재발급은 한 번만 수행하고,
// 나머지 요청은 큐에서 대기했다가 새 토큰으로 재시도한다. (single-flight)
let isRefreshing = false;
let pendingQueue = [];

const processQueue = (error, newToken = null) => {
  pendingQueue.forEach(({ resolve, reject }) => {
    if (error) reject(error);
    else resolve(newToken);
  });
  pendingQueue = [];
};

const redirectToLogin = () => {
  // React Router 밖(모듈 스코프)이라 window 로 이동 처리
  if (window.location.pathname !== LOGIN_PATH) {
    window.location.href = LOGIN_PATH;
  }
};

/**
 * 새 accessToken 을 발급받는다.
 * - refreshToken 은 httpOnly 쿠키이므로 body 로 보내지 않는다.
 *   withCredentials: true 로 요청하면 브라우저가 쿠키를 자동 첨부한다.
 * - 인터셉터 무한 루프를 막기 위해 axiosInstance 가 아닌 순수 axios 로 호출한다.
 * - 백엔드 응답은 CustomResponse( { result: {...} } ) 로 래핑되므로 result 안에서 토큰을 꺼낸다.
 */
const reissueAccessToken = async () => {
  const { data } = await axios.post(
    `${BASE_URL}${REISSUE_URL}`,
    null,
    {
      headers: { "Content-Type": "application/json" },
      withCredentials: true,
      // 멈춘 리프레시가 isRefreshing 을 영구 true 로 만들어 이후 401 이 큐에
      // 영구 적체(앱 wedge)되는 것을 막는다. 인스턴스 timeout(10000) 과 동일하게 둔다.
      timeout: 10000,
    },
  );

  const payload = data?.result ?? data; // 래핑 여부와 무관하게 동작
  const newAccessToken = payload?.accessToken;

  if (!newAccessToken) {
    throw new Error("재발급 응답에 accessToken 이 없습니다.");
  }

  tokenStorage.setAccessToken(newAccessToken);
  return newAccessToken;
};

axiosInstance.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;

    // 네트워크 오류 등 응답이 없는 경우는 그대로 반환
    if (!error.response || !originalRequest) {
      return Promise.reject(error);
    }

    const isUnauthorized = error.response.status === 401;
    const isRetryable = !originalRequest._retry;

    // /auth/* 는 재발급 대상이 아니다.
    // - /auth/refresh: 재발급 자체가 401 이면 더 시도할 게 없다
    // - /auth/login/*: 인가코드 만료가 401 로 오는데(AUTH401_4) 여기서 재발급을 시도하면
    //   아직 없는 refreshToken 으로 요청이 한 번 더 나가고, 그 실패가 토큰 정리 +
    //   "/" 하드 리다이렉트로 이어져 호출부의 catch 가 진짜 사유를 못 받는다
    // - /auth/logout: 이미 로그아웃 흐름이라 재발급할 이유가 없다
    // url 은 보통 상대경로(예: "/auth/refresh")지만 절대 URL 일 수도 있으므로
    // pathname 을 추출해 prefix 로 매치한다 — substring 매치는 "/projects/5/auth/..."
    // 같은 미래 라우트를 오탐한다.
    let isAuthCall = false;
    if (originalRequest.url) {
      try {
        isAuthCall = new URL(originalRequest.url, BASE_URL).pathname.startsWith(
          "/auth/",
        );
      } catch {
        isAuthCall = false;
      }
    }

    // 401 이 아니거나, 이미 재시도했거나, 인증 엔드포인트면 그대로 실패 처리
    // (refreshToken 은 httpOnly 쿠키라 JS 로 존재 여부를 확인할 수 없으므로,
    //  일단 재발급을 시도하고 쿠키가 없거나 만료됐으면 그때 401 로 실패 처리한다.)
    if (!isUnauthorized || !isRetryable || isAuthCall) {
      return Promise.reject(error);
    }

    // 이미 다른 요청이 재발급을 진행 중이면 큐에서 대기
    if (isRefreshing) {
      return new Promise((resolve, reject) => {
        pendingQueue.push({ resolve, reject });
      }).then((newToken) => {
        // 재요청이 다시 401 이면 isRetryable(!_retry) 가드에 걸려 리프레시
        // 재진입 없이 실패하도록, 리더와 동일하게 재시도 전에 _retry 를 세운다.
        originalRequest._retry = true;
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        return axiosInstance(originalRequest);
      });
    }

    originalRequest._retry = true;
    isRefreshing = true;

    try {
      const newAccessToken = await reissueAccessToken();
      processQueue(null, newAccessToken); // 대기 중이던 요청들 깨우기
      originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
      return axiosInstance(originalRequest); // 원래 요청 재시도
    } catch (refreshError) {
      processQueue(refreshError, null); // 대기 요청들도 실패 처리
      tokenStorage.clear();
      redirectToLogin();
      return Promise.reject(refreshError);
    } finally {
      isRefreshing = false;
    }
  },
);

export default axiosInstance;
