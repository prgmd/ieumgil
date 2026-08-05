# 인증 API

> `/auth` — 소셜 로그인 · 토큰 재발급 · 로그아웃

---

## 공통 규약

- **응답 래퍼 `CustomResponse`**: 모든 응답은 `{ "isSuccess", "code", "message", "result" }` 형태.
  성공은 `isSuccess: true` + `result`에 데이터, 실패는 `isSuccess: false` + `code`로 사유 구분.
  아래 명세의 Response 예시는 이 래퍼를 포함해 표기한다.
  **`result`가 없는 응답은 `"result": null`이 아니라 키 자체가 빠진다** (`@JsonInclude(NON_NULL)`).
- **`message`는 상황별 문구가 아니다**: `GeneralSuccessCode`의 고정 문구만 나간다 —
  `COMMON200` "요청에 성공했습니다.", `COMMON201` "자원이 생성되었습니다.".
  사용자에게 보여줄 문구는 프론트가 직접 가진다.
- **인증 헤더**: `Authorization: Bearer {accessToken}` (로그인·재발급 제외).
- **베이스 경로**: 버전 prefix를 쓰지 않는다. 백엔드 매핑은 `/api/auth`,
  프론트 `VITE_API_BASE_URL` 기본값은 `http://localhost:8080/api`.
- **토큰 정책(AUTH-04)**: Access 30분 — 응답 바디로만 전달, 프론트는 메모리 보관.
  Refresh 14일 — `HttpOnly; Secure; SameSite=Lax; Path=/api/auth` 쿠키. 프론트는 JS로 다루지 않으며 `withCredentials: true`로 자동 전송.

---

## 엔드포인트 목록

| Method | Path | 설명 | Auth |
|---|---|---|---|
| POST | `/api/auth/login/kakao` | 카카오 인가 코드로 로그인/회원가입 | No |
| POST | `/api/auth/login/naver` | 네이버 인가 코드로 로그인/회원가입 **(⚠️ 미구현 — 네이버 로그인 v1 범위 제외 확정, 백엔드 코드 0건)** | No |
| POST | `/api/auth/refresh` | Access 재발급 (RTR — refreshToken 교체) | Cookie |
| POST | `/api/auth/logout` | Refresh 무효화 + 쿠키 만료 | Yes |
| POST | `/api/auth/test-token` | **dev 전용** — 시드 멤버 JWT 발급 **(⚠️ 미구현 — 백엔드 매핑 없음)** | No (local) |

> 내 정보 조회(`GET /api/members/me`)·회원 탈퇴(`DELETE /api/members/me`)는 [my-group-api.md](my-group-api.md)로 이동.

> 카카오/네이버 인가 URL 리다이렉트는 프론트엔드가 처리한다. 콜백에서 받은 인가 코드(`code`)를 로그인 API로 전달하면 백엔드가 소셜 토큰 발급 API·사용자정보 API를 `WebClient`로 직접 호출한다. 소셜 개발자 콘솔의 Redirect URI는 프론트엔드 콜백 주소로 등록한다.

---

## 상세 명세

### POST /api/auth/login/kakao

카카오 인가 코드로 로그인/회원가입. BE가 카카오 토큰 발급 API·사용자정보 API 직접 호출 → `kakao_id`로 기존 회원 조회, 없으면 신규 생성 → JWT 발급.

**Request Body:**
```json
{ "code": "abcdef123456" }
```

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "userId": 1,
    "nickname": "동혁",
    "profileImageUrl": "https://k.kakaocdn.net/...",
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "isNewUser": false
  }
}
```
> 응답 헤더에 `Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Lax; Path=/api/auth` 포함.
> `isNewUser: true`이면 방금 신규 생성된 회원.
>
> ⚠️ 이 응답의 `userId`·`profileImageUrl`은 `GET /api/members/me`의 `id`·`profileImg`와
> **같은 값을 다른 이름으로** 내려준다. 프론트는 로그인 응답에서 `accessToken`만 쓰고
> 사용자 정보는 `/members/me`로 다시 받으므로 현재는 문제가 없지만, 이 응답의 사용자
> 정보를 쓰려면 이름 차이를 감안해야 한다.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | `code` 누락 또는 공백 (`@Valid` 실패 — `result`에 필드별 메시지) |
| `AUTH401_4` | 401 | 인가 코드 만료·이미 사용됨 — 카카오 토큰 API가 4xx 응답 |
| `AUTH502_1` | 502 | 카카오 토큰 API 5xx·타임아웃 |
| `AUTH502_2` | 502 | 카카오 사용자정보 API 호출 실패 |

---

### POST /api/auth/login/naver

> ⚠️ **미구현 — v1 범위 제외 확정.** 아래는 계획 명세일 뿐 백엔드 구현이 없다(`AUTH-03`, 코드 0건). 에러코드 `NAVER_AUTH_FAILED`·`SOCIAL_SERVER_ERROR`도 실제 enum에 없다.

네이버 인가 코드로 로그인/회원가입. 흐름은 카카오와 동일.

**Request Body:**
```json
{ "code": "abcdef123456" }
```

**Response `200`:** 카카오 로그인과 동일 구조(`result`: `userId`, `nickname`, `profileImageUrl`, `accessToken`, `isNewUser`) + `Set-Cookie: refreshToken`.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `code` 누락 또는 공백 |
| `NAVER_AUTH_FAILED` | 401 | 인가 코드 만료/무효 |
| `SOCIAL_SERVER_ERROR` | 502 | 네이버 API 호출 실패 |

---

### POST /api/auth/refresh

Access 토큰 재발급(30분 만료 시). **RTR** — refreshToken도 함께 교체한다. 저장된 토큰과 불일치하면 탈취로 간주해 해당 사용자의 refresh를 삭제하고 재로그인을 유도한다.

**Request:** 바디 없음. `Cookie: refreshToken=...` 필요 (브라우저 자동 전송).

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "accessToken": "eyJhbGciOiJIUzI1NiJ9..." }
}
```
> 응답 헤더에 새 `Set-Cookie: refreshToken` 포함(기존 토큰은 무효화).

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `AUTH401_3` | 401 | 쿠키 없음 · 만료 · 저장값 불일치 |

> 쿠키 없음과 저장값 불일치를 **코드로 구분하지 않는다** — 어느 쪽인지 알려주는 것은
> 공격자에게 정보를 주는 쪽이고, 프론트도 두 경우 모두 재로그인으로 처리한다.
> 저장값 불일치일 때는 탈취로 간주해 해당 사용자의 refresh를 삭제한다.

---

### POST /api/auth/logout

Refresh 무효화(Redis 삭제) + refreshToken 쿠키 만료.

**Request:** 바디 없음.

**Response `200`:**
```json
{ "isSuccess": true, "code": "COMMON200", "message": "요청에 성공했습니다." }
```
> 응답 헤더에 `Set-Cookie: refreshToken=; Max-Age=0` 포함.

---

### POST /api/auth/test-token

> ⚠️ **미구현** — 백엔드에 이 매핑이 없다(코드 0건). 아래는 계획 명세다. 동시 편집 수동 검증이 이 엔드포인트를 전제한다면 먼저 구현이 필요하다.

**dev 전용 (`@Profile("local")`)** — 시드 멤버용 진짜 JWT 발급. Swagger에서 소셜 콜백 없이 "멤버 n으로 로그인" 상태를 즉시 구성. 동시 편집 테스트 시 멤버 1·2 토큰을 각각 받아 두 탭으로 검증.

**Query Params:**

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `memberId` | Y | 시드 멤버 ID |

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "accessToken": "eyJhbGciOiJIUzI1NiJ9..." }
}
```
> 운영(prod) 프로필에서는 라우팅되지 않음(404).
