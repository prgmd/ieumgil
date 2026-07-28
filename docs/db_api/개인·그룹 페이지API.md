# 개인 · 그룹 페이지 API

> `/groups`, `/projects` — 개인 페이지(내 그룹) · 그룹 페이지(멤버 · 프로젝트 카드 관리)

---

## 공통 규약

- **응답 래퍼 `CustomResponse`**: `{ "isSuccess", "code", "message", "result" }`. (인증API.md 참조)
- **인증 헤더**: 모든 엔드포인트 `Authorization: Bearer {accessToken}` 필요.
- **멤버십 검증**: `{groupId}`·`{projectId}` 경로는 AOP `@GroupMember`로 요청자의 그룹 소속을 검증한다. 비멤버 접근은 `403 FORBIDDEN`.
- **범위 구분**: 이 문서는 개인/그룹 페이지에서 수행하는 **그룹 관리 + 프로젝트 카드 관리**를 다룬다. 프로젝트 보드 내부(스냅샷·블록·실시간·예산)는 [대시보드API.md](대시보드API.md) 참조.

---

## 엔드포인트 목록

| Method | Path | 설명 | Auth |
|---|---|---|---|
| GET | `/api/groups` | 내 그룹 목록 (개인 페이지) | Yes |
| POST | `/api/groups/join` | 초대 코드로 그룹 입장 | Yes |
| POST | `/api/groups` | 그룹 생성 | Yes |
| PATCH | `/api/groups/{groupId}` | 그룹명 수정 (ADMIN) | Yes |
| DELETE | `/api/groups/{groupId}` | 그룹 소프트 삭제 (ADMIN) | Yes |
| GET | `/api/groups/{groupId}/members` | 멤버 목록 + 초대 코드 | Yes |
| POST | `/api/groups/{groupId}/invite-code` | 초대 코드 재발급 (ADMIN) | Yes |
| DELETE | `/api/groups/{groupId}/members/{memberId}` | 멤버 방출 (ADMIN) | Yes |
| DELETE | `/api/groups/{groupId}/members/me` | 자발적 탈퇴 | Yes |
| GET | `/api/groups/{groupId}/projects` | 프로젝트 카드 목록 | Yes |
| POST | `/api/groups/{groupId}/projects` | 프로젝트 생성 | Yes |
| PATCH | `/api/projects/{projectId}` | 프로젝트 이름·기간 수정 | Yes |
| DELETE | `/api/projects/{projectId}` | 프로젝트 삭제 | Yes |

---

## 상세 명세

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

초대 코드로 그룹 입장. 정원(최대 10명) 검증은 그룹 행 `SELECT ... FOR UPDATE`로 동시 가입 경합 방지.

**Request Body:**
```json
{ "inviteCode": "ABCD2345" }
```

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "그룹에 입장했습니다.",
  "result": { "groupId": 5, "name": "동아리 친구들" }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `INVALID_FORMAT` | 400 | 코드 형식 오류(영대문자·숫자 8자리 아님) |
| `NOT_FOUND` | 404 | 존재하지 않는 코드 |
| `EXPIRED` | 410 | 만료된 코드 — 방장에게 재발급 요청 |
| `GROUP_FULL` | 409 | 정원(10명) 초과 |
| `ALREADY_MEMBER` | 409 | 이미 소속된 그룹 |

---

### POST /api/groups

그룹 생성 → 생성자를 ADMIN으로 등록하고 초대 코드를 발급해 응답.

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
  "message": "그룹이 생성되었습니다.",
  "result": {
    "groupId": 7,
    "name": "제주 여행팀",
    "inviteCode": "ABCD2345",
    "inviteExpiresAt": "2026-08-04T12:00:00+09:00"
  }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | name 누락 또는 2~20자 범위 위반 |

---

### PATCH /api/groups/{groupId}

그룹명 수정. **ADMIN만** 가능.

**Request Body:**
```json
{ "name": "제주 여행팀 (수정)" }
```

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "그룹명이 수정되었습니다.",
  "result": { "groupId": 7, "name": "제주 여행팀 (수정)" }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | name 범위 위반 |
| `FORBIDDEN` | 403 | ADMIN이 아님 |

---

### DELETE /api/groups/{groupId}

그룹 소프트 삭제. **ADMIN만**, 오입력 방지를 위해 그룹명 일치 검증(MY-04). 스케줄러가 30일 경과분을 하드 삭제.

**Request Body:**
```json
{ "confirmName": "제주 여행팀" }
```

**Response `200`:**
```json
{ "isSuccess": true, "code": "COMMON200", "message": "그룹이 삭제되었습니다.", "result": null }
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `FORBIDDEN` | 403 | ADMIN이 아님 |
| `NAME_MISMATCH` | 400 | confirmName이 그룹명과 불일치 |

---

### GET /api/groups/{groupId}/members

멤버 목록 + 초대 코드(만료일 포함). 방장 뱃지는 `role`로 판별.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "inviteCode": "ABCD2345",
    "inviteExpiresAt": "2026-08-04T12:00:00+09:00",
    "members": [
      { "memberId": 1, "nickname": "동혁", "profileImg": "https://...", "role": "ADMIN", "online": true },
      { "memberId": 2, "nickname": "지수", "profileImg": null, "role": "MEMBER", "online": false }
    ]
  }
}
```
> `online`은 presence(Redis) 스냅샷 값. 실시간 갱신은 대시보드의 presence 토픽에서 처리.

---

### POST /api/groups/{groupId}/invite-code

초대 코드 재발급. **ADMIN만**, 기존 코드 즉시 무효(GRP-06).

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "초대 코드가 재발급되었습니다.",
  "result": { "inviteCode": "EFGH6789", "inviteExpiresAt": "2026-08-04T12:00:00+09:00" }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `FORBIDDEN` | 403 | ADMIN이 아님 |

---

### DELETE /api/groups/{groupId}/members/{memberId}

멤버 방출 — **해당 멤버의 WS 세션 강제 종료 + `MEMBER_KICKED` op 브로드캐스트**(GRP-07). **ADMIN만**. 블록은 author_id로 남아 자산 유지.

**Response `200`:**
```json
{ "isSuccess": true, "code": "COMMON200", "message": "멤버를 방출했습니다.", "result": null }
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `FORBIDDEN` | 403 | ADMIN이 아님 |
| `NOT_FOUND` | 404 | 해당 멤버가 그룹에 없음 |
| `CANNOT_KICK_SELF` | 400 | 본인은 방출 불가(탈퇴 API 사용) |

---

### DELETE /api/groups/{groupId}/members/me

자발적 탈퇴. 방장이면 트랜잭션 내 승계(joined_at 최소 멤버로 위임), 마지막 1인이면 그룹 소프트 삭제. 본인 WS 세션 종료(GRP-09).

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "그룹에서 나갔습니다.",
  "result": { "groupDeleted": false }
}
```
> `groupDeleted: true`이면 마지막 1인이 나가 그룹이 소프트 삭제됨.

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
      "transportPref": "CAR",
      "status": "PLANNING",
      "themeColor": "sunset"
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
  "transportPref": "CAR"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | Y | 프로젝트 이름 |
| `startDate` | date | Y | 시작일 |
| `endDate` | date | Y | 종료일 (시작일 이후) |
| `destination` | string | N | 여행지 |
| `budgetHeadcount` | int | Y | 정산 기준 인원(생성 폼 여행 인원) |
| `targetBudget` | int | N | 1인 목표 예산 |
| `transportPref` | enum | Y | `CAR` \| `PUBLIC` |

**Response `201`:**
```json
{
  "isSuccess": true,
  "code": "COMMON201",
  "message": "프로젝트가 생성되었습니다.",
  "result": { "projectId": 12 }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 필수 필드 누락, endDate < startDate 등 |

---

### PATCH /api/projects/{projectId}

프로젝트 이름·기간 수정. **기간 축소 시**: 서버가 사라지는 Day의 블록을 `dayNo=null`(후보)로 일괄 이동하고, `PROJECT_UPDATED` op의 payload에 `movedToPool: [blockId...]`를 실어 **op 1건으로 원자적 전파**(GRP-03). 사라진 Day의 `day_settings` 키도 함께 제거. → 실시간 전파 상세는 [대시보드API.md](대시보드API.md) 참조.

**Request Body:** (부분 갱신, 변경 필드만 전송)
```json
{ "name": "제주 4박 5일", "startDate": "2026-08-10", "endDate": "2026-08-14" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `name` | string | N | 프로젝트 이름 |
| `startDate` | date | N | 시작일 |
| `endDate` | date | N | 종료일 |

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "프로젝트가 수정되었습니다.",
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
| `VALIDATION_ERROR` | 400 | endDate < startDate 등 |
| `FORBIDDEN` | 403 | 그룹 멤버가 아님 |

---

### DELETE /api/projects/{projectId}

프로젝트 삭제. **DONE 상태는 ADMIN만**(GRP-04). **`PROJECT_DELETED` op 브로드캐스트** — 보고 있던 멤버는 그룹 페이지로 리다이렉트.

**Response `200`:**
```json
{ "isSuccess": true, "code": "COMMON200", "message": "프로젝트가 삭제되었습니다.", "result": null }
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `FORBIDDEN` | 403 | DONE 상태를 ADMIN이 아닌 멤버가 삭제 시도 |
