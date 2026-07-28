# 인증 API

> `/auth`, `/members` — 소셜 로그인 · 토큰 재발급 · 회원 정보/탈퇴

---

## 공통 규약

- **응답 래퍼 `CustomResponse`**: 모든 응답은 `{ "isSuccess", "code", "message", "result" }` 형태.
  성공은 `isSuccess: true` + `result`에 데이터, 실패는 `isSuccess: false` + `code`로 사유 구분.
  아래 명세의 Response 예시는 이 래퍼를 포함해 표기한다.
- **인증 헤더**: `Authorization: Bearer {accessToken}` (로그인·재발급 제외).
- **버전 prefix**: 프론트 `VITE_API_BASE_URL = /api/v0`. 아래 경로는 설계 v3 표기를 따른다.
- **토큰 정책(AUTH-04)**: Access 30분 — 응답 바디로만 전달, 프론트는 메모리 보관.
  Refresh 14일 — `HttpOnly; Secure; SameSite=Lax; Path=/api/v0/auth` 쿠키. 프론트는 JS로 다루지 않으며 `withCredentials: true`로 자동 전송.

---

## 엔드포인트 목록

| Method | Path | 설명 | Auth |
|---|---|---|---|
| POST | `/api/v0/auth/login/kakao` | 카카오 인가 코드로 로그인/회원가입 | No |
| POST | `/api/v0/auth/login/naver` | 네이버 인가 코드로 로그인/회원가입 | No |
| POST | `/api/v0/auth/refresh` | Access 재발급 (RTR — refreshToken 교체) | Cookie |
| POST | `/api/v0/auth/logout` | Refresh 무효화 + 쿠키 만료 | Yes |
| POST | `/api/v0/auth/test-token` | **dev 전용** — 시드 멤버 JWT 발급 | No (local) |
| GET | `/api/members/me` | 내 정보 조회 | Yes |
| DELETE | `/api/members/me` | 회원 탈퇴 | Yes |

> 카카오/네이버 인가 URL 리다이렉트는 프론트엔드가 처리한다. 콜백에서 받은 인가 코드(`code`)를 로그인 API로 전달하면 백엔드가 소셜 토큰 발급 API·사용자정보 API를 `WebClient`로 직접 호출한다. 소셜 개발자 콘솔의 Redirect URI는 프론트엔드 콜백 주소로 등록한다.

---

## 상세 명세

### POST /api/v0/auth/login/kakao

카카오 인가 코드로 로그인/회원가입. BE가 카카오 토큰 발급 API·사용자정보 API 직접 호출 → `provider`+`provider_id`로 기존 회원 조회, 없으면 신규 생성 → JWT 발급.

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
> 응답 헤더에 `Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Lax; Path=/api/v0/auth` 포함.
> `isNewUser: true`이면 방금 신규 생성된 회원.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `code` 누락 또는 공백 |
| `KAKAO_AUTH_FAILED` | 401 | 인가 코드 만료/무효 — 카카오 토큰 발급 실패 |
| `SOCIAL_SERVER_ERROR` | 502 | 카카오 토큰/사용자정보 API 호출 실패 |

---

### POST /api/v0/auth/login/naver

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

### POST /api/v0/auth/refresh

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
| `REFRESH_TOKEN_MISSING` | 401 | refreshToken 쿠키 없음 |
| `REFRESH_TOKEN_INVALID` | 401 | 만료 또는 저장값 불일치 → 탈취 간주, refresh 삭제 |

---

### POST /api/v0/auth/logout

Refresh 무효화(Redis 삭제) + refreshToken 쿠키 만료.

**Request:** 바디 없음.

**Response `200`:**
```json
{ "isSuccess": true, "code": "COMMON200", "message": "로그아웃 되었습니다.", "result": null }
```
> 응답 헤더에 `Set-Cookie: refreshToken=; Max-Age=0` 포함.

---

### POST /api/v0/auth/test-token

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

---

### GET /api/members/me

로그인한 회원 본인 정보 조회.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "id": 1,
    "nickname": "동혁",
    "profileImg": "https://k.kakaocdn.net/..."
  }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `UNAUTHORIZED` | 401 | accessToken 누락/만료 |

---

### DELETE /api/members/me

회원 탈퇴 — ERD `MEMBER` 탈퇴 정책 수행(`provider_id` 센티널 치환, `nickname` "탈퇴한 멤버" 교체, `profile_img` null, `deleted_at` 기록). 행은 유지되어 블록 작성자 표기가 보존된다.

**Response `204`:** 본문 없음.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `UNAUTHORIZED` | 401 | accessToken 누락/만료 |
