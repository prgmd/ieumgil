# 개인 · 그룹 페이지 API

> `/groups`, `/projects`, `/members` — 개인 페이지(내 그룹 · 내 정보) · 그룹 페이지(멤버 · 프로젝트 카드 관리)

---

## 공통 규약

- **응답 래퍼 `CustomResponse`**: `{ "isSuccess", "code", "message", "result" }`. ([auth-api.md](auth-api.md) 참조)
  `result`가 없는 응답은 `"result": null`이 아니라 **키 자체가 빠진다**(`@JsonInclude(NON_NULL)`).
- **`message`는 상황별 문구가 아니다**: `COMMON200` "요청에 성공했습니다." / `COMMON201` "자원이 생성되었습니다."
  두 고정 문구만 나간다. 사용자에게 보여줄 문구는 프론트가 직접 가진다.
- **인증 헤더**: 모든 엔드포인트 `Authorization: Bearer {accessToken}` 필요. 누락·만료·위변조는 물론 **탈퇴한 계정의 유효 토큰**도 `COMMON401`이다(`ActiveUserChecker` — [auth-api.md](auth-api.md) 공통 규약 참조).
- **멤버십 검증**: `{groupId}`·`{projectId}` 경로는 AOP `@GroupMember`로 요청자의 그룹 소속을 검증한다.
  **존재 확인이 먼저다** — 없는 그룹은 `GROUP404`, 있지만 비멤버면 `GROUP403`.
  (순서를 바꾸면 없는 그룹에 403이 나가 프론트가 권한 문제로 오해한다.)
- **권한 모델(flat)**: 방장/관리자 개념이 없다. 그룹의 **모든 멤버가 동등**하게 그룹명 수정·초대 코드 재발급·그룹/프로젝트 삭제를 수행할 수 있다. 멤버 강제 방출(kick)은 없고 **본인 탈퇴(self-leave)만** 가능하며, 마지막 1인이 나가면 그룹은 **하드 삭제**(즉시 완전 삭제)된다.
- **범위 구분**: 이 문서는 개인/그룹 페이지에서 수행하는 **내 정보 + 그룹 관리 + 프로젝트 카드 관리**를 다룬다. 프로젝트 보드 내부(스냅샷·블록·실시간·예산)는 [dashboard-api.md](dashboard-api.md) 참조.

---

## 엔드포인트 목록

| Method | Path | 설명 | Auth |
|---|---|---|---|
| GET | `/api/members/me` | 내 정보 조회 | Yes |
| DELETE | `/api/members/me` | 회원 탈퇴 | Yes |
| GET | `/api/groups` | 내 그룹 목록 (개인 페이지) | Yes |
| POST | `/api/groups/join` | 초대 코드로 그룹 입장 | Yes |
| POST | `/api/groups` | 그룹 생성 | Yes |
| PATCH | `/api/groups/{groupId}` | 그룹명 수정 | Yes |
| GET | `/api/groups/{groupId}/members` | 멤버 목록 + 초대 코드 | Yes |
| POST | `/api/groups/{groupId}/invite-code` | 초대 코드 재발급 | Yes |
| DELETE | `/api/groups/{groupId}/members/me` | 자발적 탈퇴 | Yes |
| GET | `/api/groups/{groupId}/projects` | 프로젝트 카드 목록 | Yes |
| POST | `/api/groups/{groupId}/projects` | 프로젝트 생성 | Yes |
| PATCH | `/api/projects/{projectId}` | 프로젝트 이름·기간 수정 | Yes |
| DELETE | `/api/projects/{projectId}` | 프로젝트 삭제 | Yes |

---

## 상세 명세

### GET /api/members/me

로그인한 회원 본인 정보 조회 (개인 페이지).

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
| `COMMON401` | 401 | accessToken 누락/만료 |

---

### DELETE /api/members/me

회원 탈퇴 — ERD `MEMBER` 탈퇴 정책 수행(`kakao_id` null 처리, `nickname` "탈퇴한 멤버" 교체, `profile_image_url` null, `deleted_at` 기록). 행은 유지되어 블록 작성자 표기가 보존된다. 소속 그룹에서는 자동 탈퇴 처리되며, 마지막 1인이던 그룹은 하드 삭제된다(§ `DELETE /api/groups/{groupId}/members/me` 참조). Refresh 토큰 삭제 + **본인 WS 세션 강제 종료**도 함께 수행한다.

**Response `204`:** 본문 없음. 응답 헤더에 `Set-Cookie: refreshToken=; Max-Age=0`(쿠키 만료) 포함.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON401` | 401 | accessToken 누락/만료 |

---

### GET /api/groups

내 그룹 목록(개인 페이지). 그룹명, 멤버 수, 완료 여부와 무관한 전체 프로젝트 수, 멤버 아바타.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": [
    {
      "groupId": 1,
      "name": "A107 친구들",
      "memberCount": 4,
      "tripCount": 3,
      "members": [
        { "memberId": 1, "nickname": "동혁", "profileImg": "https://..." },
        { "memberId": 2, "nickname": "지수", "profileImg": null }
      ]
    }
  ]
}
```
> `members`는 아바타 표시용 요약 목록. 상세는 `/api/groups/{groupId}/members` 사용.

---

### POST /api/groups/join

초대 코드로 그룹 입장. 검증 순서가 곧 응답 코드 순서다 — 없는 코드(404) → 만료(410) → 이미 멤버(409) → 정원(409).

> **알려진 한계**: 정원(최대 10명) 검증에 비관적 락을 걸지 않는다. 9명일 때 두 명이 동시에
> 들어오면 11명이 될 수 있다. 실질적 피해가 없어 일정을 우선해 감수하기로 결정했다(2026-07-29).
> 필요해지면 리포지토리 메서드에 `@Lock(PESSIMISTIC_WRITE)`만 추가하면 된다.

**Request Body:**
```json
{ "inviteCode": "ABCD2345" }
```

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "groupId": 5, "name": "동아리 친구들" }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | 코드 형식 오류(영대문자·숫자 8자리 아님) — `@Pattern` 위반 |
| `GROUP404_1` | 404 | 존재하지 않는 코드 |
| `GROUP410` | 410 | 만료된 코드 — 멤버에게 재발급 요청 |
| `GROUP409` | 409 | 이미 소속된 그룹 |
| `GROUP409_1` | 409 | 정원(10명) 초과 |

---

### POST /api/groups

그룹 생성 → 생성자를 첫 멤버로 등록하고 초대 코드를 발급해 응답.

**Request Body:**
```json
{ "name": "제주 여행팀" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | Y | 2~20자 |

**Response `201`:**
```json
{
  "isSuccess": true,
  "code": "COMMON201",
  "message": "자원이 생성되었습니다.",
  "result": {
    "groupId": 7,
    "name": "제주 여행팀",
    "inviteCode": "ABCD2345",
    "inviteExpiresAt": "2026-08-04T12:00:00"
  }
}
```
> 이 응답에는 목록(`GET /api/groups`)의 `memberCount`·`tripCount`·`members`가 없다.
> 생성 직후 값은 "멤버는 생성자 1명, 프로젝트 0개"로 확정돼 있어, 프론트는 목록을
> 다시 조회하지 않고 이 응답에 그 값을 채워 카드를 만든다.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | name 누락 또는 2~20자 범위 위반 |

---

### PATCH /api/groups/{groupId}

그룹명 수정. 모든 멤버 가능(flat 모델).

**Request Body:**
```json
{ "name": "제주 여행팀 (수정)" }
```

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "groupId": 7, "name": "제주 여행팀 (수정)" }
}
```
> 응답에 `groupId`·`name`만 담기므로, 프론트는 목록의 기존 항목을 통째로 교체하지 않고
> `name`만 덮어써야 한다(교체하면 `members`·`tripCount`가 사라진다).

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | name 범위 위반 |

---

### GET /api/groups/{groupId}/members

멤버 목록 + 초대 코드(만료일 포함).

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "inviteCode": "ABCD2345",
    "inviteExpiresAt": "2026-08-04T12:00:00",
    "members": [
      { "memberId": 1, "nickname": "동혁", "profileImg": "https://...", "online": false },
      { "memberId": 2, "nickname": "지수", "profileImg": null, "online": false }
    ]
  }
}
```
> ⚠️ **이 응답의 `online`은 항상 `false`다** — 그룹 멤버 목록은 presence와 연동되어 있지 않다
> (`GroupConverter`가 고정값을 넣는다). presence 실값은 대시보드 스냅샷
> (`GET /api/projects/{projectId}`, [dashboard-api.md](dashboard-api.md))의 멤버 목록에서만 내려온다.
> 그룹 페이지에서 접속 상태를 표시하려면 별도 연동이 필요하다.

---

### POST /api/groups/{groupId}/invite-code

초대 코드 재발급. 모든 멤버 가능(flat), 기존 코드 즉시 무효(GRP-06).

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "inviteCode": "EFGH6789", "inviteExpiresAt": "2026-08-04T12:00:00" }
}
```

---

### DELETE /api/groups/{groupId}/members/me

자발적 탈퇴. 마지막 1인이 나가면 그룹 **하드 삭제**(복구할 멤버가 없으므로 즉시 완전 삭제 — 프로젝트·블록도 CASCADE 삭제). 본인 WS 세션 종료(GRP-09). 방장 개념이 없어 승계 로직은 없다.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "groupDeleted": false }
}
```
> `groupDeleted: true`이면 마지막 1인이 나가 그룹이 하드 삭제됨(즉시 완전 삭제).

---

### GET /api/groups/{groupId}/projects

프로젝트 카드 목록.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": [
    {
      "projectId": 1,
      "name": "제주 3박 4일",
      "startDate": "2026-08-10",
      "endDate": "2026-08-13",
      "destination": "제주",
      "budgetHeadcount": 4,
      "transportPrefs": ["CAR"],
      "status": "PLANNING"
    }
  ]
}
```
> 카드 클릭 시 `status`가 `PLANNING`이면 대시보드 열기, `DONE`이면 열람 전용.

---

### POST /api/groups/{groupId}/projects

프로젝트 생성. 인원 입력은 `budgetHeadcount` 초기값으로 저장.

**Request Body:**
```json
{
  "name": "제주 3박 4일",
  "startDate": "2026-08-10",
  "endDate": "2026-08-13",
  "destination": "제주",
  "budgetHeadcount": 4,
  "targetBudget": 300000,
  "transportPrefs": ["CAR"]
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | Y | 프로젝트 이름, 100자 이하 |
| `startDate` | date | Y | 시작일 |
| `endDate` | date | Y | 종료일 (시작일 이후) |
| `destination` | string | N | 여행지, 100자 이하 |
| `budgetHeadcount` | int | Y | 정산 인원(1인당 표시용, 생성 폼 여행 인원), 양수 |
| `targetBudget` | int | N | 프로젝트 전체 목표 예산, 0 이상 |
| `transportPrefs` | array\<enum\> | Y | 이동수단 선호(복수 선택 가능), 값은 `CAR`\|`PUBLIC`, 최소 1개 |

**Response `201`:**
```json
{
  "isSuccess": true,
  "code": "COMMON201",
  "message": "자원이 생성되었습니다.",
  "result": { "projectId": 12 }
}
```
> 응답에 `projectId`만 담기므로, 프론트는 카드에 필요한 나머지 필드를 방금 보낸 요청값으로
> 채운다. `status`는 항상 `PLANNING`으로 시작한다.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | 필수 필드 누락, 범위 위반 (`@Valid` 실패, `transportPrefs` 빈 배열 포함) |
| `COMMON400_4` | 400 | `transportPrefs` 원소에 `CAR`·`PUBLIC` 외의 값(빈 문자열 포함) — enum 변환 실패 |
| `PROJECT400` | 400 | endDate < startDate |

---

### PATCH /api/projects/{projectId}

프로젝트 이름·기간·여행지·이동수단 수정. **기간 축소 시**: 서버가 사라지는 Day의 블록을 `startOffsetMinutes=null`(후보)로 일괄 이동하고(orderKey는 보존), `PROJECT_UPDATED` op의 payload에 `movedToPool: [blockId...]`를 실어 **op 1건으로 원자적 전파**(GRP-03). → 실시간 전파 상세는 [dashboard-api.md](dashboard-api.md) 참조.

**Request Header(선택):** `X-Client-Id` — 브로드캐스트 op의 에코 판별용([dashboard-api.md](dashboard-api.md) 공통 규약 참조). `DELETE`도 동일.

**Request Body:** (부분 갱신, 변경 필드만 전송)
```json
{ "name": "제주 4박 5일", "startDate": "2026-08-10", "endDate": "2026-08-14", "destination": "제주", "transportPrefs": ["CAR", "PUBLIC"] }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | N | 프로젝트 이름, 1~100자 |
| `startDate` | date | N | 시작일 |
| `endDate` | date | N | 종료일 |
| `destination` | string | N | 여행지, 100자 이하. `null`이면 미변경 |
| `transportPrefs` | array\<enum\> | N | 이동수단 선호(복수 선택 가능), 값은 `CAR`\|`PUBLIC`. `null`이면 미변경 |

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "projectId": 12,
    "name": "제주 4박 5일",
    "startDate": "2026-08-10",
    "endDate": "2026-08-14",
    "movedToPool": []
  }
}
```
> 기간 축소로 후보로 밀려난 블록이 있으면 `movedToPool`에 blockId 목록.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | name·destination 범위 위반 (`@Valid` 실패) |
| `COMMON400_4` | 400 | `transportPrefs` 원소에 `CAR`·`PUBLIC` 외의 값 — enum 변환 실패 |
| `PROJECT400` | 400 | endDate < startDate |
| `GROUP403` | 403 | 프로젝트가 속한 그룹의 멤버가 아님 |
| `PROJECT404` | 404 | 존재하지 않는 프로젝트 |

---

### DELETE /api/projects/{projectId}

프로젝트 **소프트 삭제**(그룹 소프트 삭제와 동일한 방식 — 행은 유지, `deleted_at` 기록). 모든 멤버 가능(flat 모델). **`PROJECT_DELETED` op 브로드캐스트** — 보고 있던 멤버는 그룹 페이지로 리다이렉트. `X-Client-Id` 헤더는 PATCH와 동일하게 선택.

**Response `200`:**
```json
{ "isSuccess": true, "code": "COMMON200", "message": "요청에 성공했습니다." }
```
