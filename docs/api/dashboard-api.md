# 대시보드 API

> `/projects/{projectId}`, `/blocks`, `/places`, `/transit`, `/trains`, WebSocket — 프로젝트 보드(실시간 협업)

프로젝트 대시보드는 블록 편집·지도·예산·챗봇·보이스가 결합된 실시간 협업 보드다. **변경 요청은 REST, 전파는 STOMP 브로드캐스트**로 처리한다(서버 권위 모델: 검증→DB→seq op 브로드캐스트).

---

## 공통 규약

- **응답 래퍼 `CustomResponse`**: `{ "isSuccess", "code", "message", "result" }`. (인증API.md 참조)
- **인증 헤더**: 모든 REST 엔드포인트 `Authorization: Bearer {accessToken}` 필요. WS는 CONNECT 헤더로 검증.
- **멤버십 검증**: 프로젝트가 속한 그룹의 멤버만 접근(REST AOP `@GroupMember` + WS `ChannelInterceptor`). 비멤버는 `403`(REST) / 프레임 거부(WS).
- **X-Client-Id**: 변경 요청 헤더에 브라우저 탭 UUID를 실어 보내면, 요청자 본인은 브로드캐스트 op에서 clientId로 자기 op를 스킵(낙관적 UI 중복 적용 방지).
- **범위 구분**: 프로젝트 카드 목록·생성·이름/기간 수정·삭제는 [개인·그룹 페이지API.md](개인·그룹%20페이지API.md) 참조.

---

## 엔드포인트 목록

### 프로젝트 보드

| Method | Path | 설명 | Auth |
|---|---|---|---|
| GET | `/api/projects/{projectId}` | 대시보드 스냅샷 (최초 로딩·재연결 재로딩) | Yes |
| GET | `/api/projects/{projectId}/ops?afterSeq={n}` | 유실 op 재전송 (재연결·seq 갭) | Yes |
| PATCH | `/api/projects/{projectId}/status` | `PLANNING↔DONE` 양방향 전환 | Yes |
| PATCH | `/api/projects/{projectId}/budget-headcount` | 정산 인원(1인당 표시용) 지정/동기화 | Yes |

### 블록

| Method | Path | 설명 | Auth |
|---|---|---|---|
| POST | `/api/projects/{projectId}/blocks` | 블록 생성 (지도/직접/교통) | Yes |
| PATCH | `/api/blocks/{blockId}/fields` | 필드 단위 LWW 배치 갱신 | Yes |
| PATCH | `/api/blocks/{blockId}/position` | 블록 이동 (체인 재정렬/후보↔체인) | Yes |
| DELETE | `/api/blocks/{blockId}` | 블록 소프트 삭제 (tombstone) | Yes |
| POST | `/api/blocks/{blockId}/detail-lock` | 세부 내용 텍스트 락 획득 | Yes |
| PUT | `/api/blocks/{blockId}/detail-lock` | 락 하트비트 (TTL 연장) | Yes |
| DELETE | `/api/blocks/{blockId}/detail-lock` | 락 해제 | Yes |

### 외부 연동 프록시 (API 키 서버 은닉)

| Method | Path | 설명 | Auth |
|---|---|---|---|
| GET | `/api/places?query=&lat=&lng=` | 카카오 로컬 keyword 검색 상위 5건 | Yes |
| GET | `/api/places/address?lat=&lng=` | coord2address 역지오코딩 | Yes |
| GET | `/api/transit/route?sx=&sy=&ex=&ey=&mode=` | 길찾기 소요 시간·요금 | Yes |
| GET | `/api/trains?dep=&arr=&after=` | KTX 시간표 출발 후보 | Yes |
| GET | `/api/stations?query=` | 역명 자동완성 | Yes |

### 챗봇

| Method | Path | 설명 | Auth |
|---|---|---|---|
| POST | `/api/projects/{projectId}/keywords` | 키워드 저장 + 후보 블록 자동 생성 | Yes |
| POST | `/api/projects/{projectId}/bot/chat` | 대화형 장소 추천 | Yes |

---

## 상세 명세 — 프로젝트 보드

### GET /api/projects/{projectId}

대시보드 스냅샷 — 최초 로딩·재연결 스냅샷 재로딩 겸용. `lastSeq`로 이후 op 동기화 기준을 잡는다.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "project": {
      "projectId": 12,
      "name": "제주 3박 4일",
      "destination": "제주",
      "startDate": "2026-08-10",
      "endDate": "2026-08-13",
      "transportPref": "CAR",
      "budgetHeadcount": 4,
      "targetBudget": 300000,
      "keywords": ["오름", "카페"],
      "status": "PLANNING",
      "themeColor": "sunset"
    },
    "blocks": [
      {
        "blockId": 101,
        "dayNo": 1,
        "orderKey": "a0",
        "category": "SPOT",
        "subCategory": "관광",
        "name": "성산일출봉",
        "durationMin": 90,
        "startTime": "09:00",
        "endTime": "10:30",
        "isTimeFixed": false,
        "budget": 5000,
        "detail": "매표 후 도보 30분",
        "lat": 33.4581,
        "lng": 126.9425,
        "placeId": "12345",
        "address": "제주 서귀포시 성산읍",
        "vehicleFlag": null,
        "transportMeta": null,
        "source": "KAKAO",
        "authorId": 1,
        "fieldUpdatedAt": { "budget": "2026-08-01T10:22:31.512Z" },
        "createdAt": "2026-08-01T10:00:00+09:00"
      }
    ],
    "members": [
      { "memberId": 1, "nickname": "동혁", "profileImg": "https://...", "online": true }
    ],
    "lastSeq": 1042
  }
}
```
> `dayNo: null`인 블록은 후보(POOL). 블록 정렬은 `orderKey, blockId` 순.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `FORBIDDEN` | 403 | 그룹 멤버가 아님 |
| `NOT_FOUND` | 404 | 프로젝트 없음/삭제됨 |

---

### GET /api/projects/{projectId}/ops

재연결·**seq 갭 감지 시** 유실 op 재전송(NFR-01) — `activity_log` 저장분을 그대로 반환.

**Query Params:**

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `afterSeq` | Y | 이 seq 초과분만 반환 (`WHERE seq > afterSeq ORDER BY seq`) |

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": [
    {
      "seq": 1043,
      "type": "BLOCK_FIELD_UPDATED",
      "actorId": 3,
      "clientId": "uuid",
      "payload": { "blockId": 77, "fields": { "budget": 15000 } }
    }
  ]
}
```

---

### PATCH /api/projects/{projectId}/status

`PLANNING↔DONE` 양방향 전환(GRP-10). `PROJECT_STATUS_CHANGED` op 브로드캐스트.

**Request Body:**
```json
{ "status": "DONE" }
```

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "상태가 변경되었습니다.",
  "result": { "status": "DONE", "doneAt": "2026-08-13T20:00:00+09:00" }
}
```
> `PLANNING`으로 되돌리면 `doneAt`은 null.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | status가 PLANNING/DONE 이외 |

---

### PATCH /api/projects/{projectId}/budget-headcount

정산 인원 지정/동기화(BGT-03). **정산은 프로젝트 전체(총액) 기준**이며, 이 인원은 "1인당 = 총액 ÷ 인원" 표시용이다. `BUDGET_HEADCOUNT_CHANGED` op 브로드캐스트.

**Request Body:**
```json
{ "headcount": 4 }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `headcount` | int | N | 정산 인원(1인당 표시용). **null이면 그룹 멤버 수 연동 복귀** |

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "정산 인원이 변경되었습니다.",
  "result": { "budgetHeadcount": 4 }
}
```

---

## 상세 명세 — 블록

> 모든 성공 변경은 서버가 seq를 붙여 `/topic/project/{id}`로 브로드캐스트한다. 요청 헤더 `X-Client-Id`로 요청자 본인은 스킵.

### POST /api/projects/{projectId}/blocks

블록 생성 (지도 MAP-03 / 직접 MAP-04 / 교통 BLK-04). `BLOCK_CREATED` op 브로드캐스트.

**Request Body:**
```json
{
  "category": "SPOT",
  "name": "성산일출봉",
  "dayNo": 1,
  "orderKey": "a0",
  "lat": 33.4581,
  "lng": 126.9425,
  "placeId": "12345",
  "address": "제주 서귀포시 성산읍",
  "subCategory": "관광",
  "durationMin": 90,
  "startTime": "09:00",
  "endTime": "10:30",
  "isTimeFixed": false,
  "budget": 5000,
  "vehicleFlag": null,
  "source": "KAKAO",
  "transportMeta": null
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `category` | enum | Y | `SPOT`\|`FOOD`\|`STAY`\|`ETC`\|`TRANSPORT` |
| `name` | string | Y | 블록 이름 |
| `dayNo` | int | N | null이면 후보(POOL) 생성 |
| `orderKey` | string | N | 미지정 시 서버가 말단 키 부여 |
| `lat` / `lng` | decimal | 조건부 | **장소성 카테고리(SPOT·FOOD·STAY)는 필수** |
| `placeId` / `address` | string | N | 카카오 장소 참조 |
| `subCategory` | string | N | 자유 텍스트 |
| `durationMin` | int | N | 소요시간(분), 기본 60(30분 단위) |
| `startTime` / `endTime` | string(HH:mm) | N | 일정 시작/종료 시각. 미지정 시 null(느슨한 블록) |
| `isTimeFixed` | bool | N | 시각 고정(드래그 재계산 제외) 여부, 기본 false |
| `budget` | int | N | 예산(원) — **프로젝트 전체(총액) 기준**, 기본 0 |
| `vehicleFlag` | enum | N | `START`\|`END` — **ETC 카테고리에서만 허용** |
| `source` | enum | Y | `KAKAO`\|`MANUAL`\|`BOT` |
| `transportMeta` | object | N | 교통(TRANSPORT) 블록 전용 (ERD 참조) |

**Response `201`:**
```json
{
  "isSuccess": true,
  "code": "COMMON201",
  "message": "블록이 생성되었습니다.",
  "result": { "blockId": 101, "seq": 1044 }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 장소성 카테고리 lat/lng 누락, vehicleFlag를 ETC 외 카테고리에 지정 |
| `FORBIDDEN` | 403 | 그룹 멤버가 아님 |

---

### PATCH /api/blocks/{blockId}/fields

**필드 단위 LWW 배치 갱신** — 상세 모달에서 여러 필드를 함께 저장해도 요청 1번·op 1건. 필드별로 독립 LWW 판정(스테일 필드만 미적용). `BLOCK_FIELD_UPDATED` op payload에도 적용된 필드만 포함.

**Request Body:**
```json
{
  "fields": [
    { "field": "budget", "value": 15000 },
    { "field": "name", "value": "성산일출봉 (수정)" }
  ]
}
```

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "applied": { "budget": true, "name": false } }
}
```
> `applied`가 `false`인 필드는 더 최신 값이 이미 반영돼 있어 이번 변경이 무시됨(스테일).
> LWW 대상 필드: `name`, `budget`, `durationMin`, `detail`, `startTime`, `endTime`, `isTimeFixed`, `vehicleFlag`, `transportMeta` 등.
> 드래그 재정렬 후 재계산된 시각도 이 엔드포인트로 저장한다(§ position 참조).

---

### PATCH /api/blocks/{blockId}/position

블록 이동 — 체인 재정렬 / 후보↔체인 / Day 이동(BLK-07, DAY-02). 이동 자체는 옮긴 블록 1행(`orderKey`·`dayNo`)만 UPDATE. `BLOCK_MOVED` op 브로드캐스트.

**Request Body:**
```json
{ "dayNo": 2, "orderKey": "a5" }
```
> 후보로 이동 시 `dayNo: null`.
>
> **시각 재계산(공백 보존)**: 이동 후 클라이언트는 영향받은 일반 블록(`isTimeFixed=false`)의 `startTime`/`endTime`을 앞 블록 종료 시각 + 소요시간 기준으로 다시 계산해 `PATCH /api/blocks/{blockId}/fields`로 저장한다. `isTimeFixed=true`(예약·교통 앵커)는 재계산에서 제외한다. 블록별 시각을 정본으로 저장하므로 블록 사이 공백(간격)은 그대로 유지된다.

**Response `200`:**
```json
{ "isSuccess": true, "code": "COMMON200", "message": "요청에 성공했습니다.", "result": { "blockId": 101, "seq": 1045 } }
```

---

### DELETE /api/blocks/{blockId}

블록 소프트 삭제(tombstone). `BLOCK_DELETED` op 브로드캐스트. **tombstone 이후 도착한 op는 `410 Gone`**(BLK-09).

**Response `200`:**
```json
{ "isSuccess": true, "code": "COMMON200", "message": "블록이 삭제되었습니다.", "result": null }
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `GONE` | 410 | 이미 삭제된 블록에 대한 지연 op |

---

### POST /api/blocks/{blockId}/detail-lock

세부 내용 텍스트 락 획득 (Redis `SET NX`, TTL 30s). 락 획득 = presence 토픽 편집 배지 on.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "acquired": true, "holder": 1, "ttlRemaining": 30 }
}
```
> 실패 시 `acquired: false` + 현재 holder와 잔여 TTL을 실어 클라 폴링 주기 근거 제공.

### PUT /api/blocks/{blockId}/detail-lock

락 하트비트 (10초 주기 TTL 연장). **Response `200`:** `result: { ttlRemaining: 30 }`.

### DELETE /api/blocks/{blockId}/detail-lock

락 해제 (= 편집 배지 off). **Response `200`:** `result: null`.

---

## 상세 명세 — 외부 연동 프록시

> API 키를 서버에 은닉하기 위한 프록시. 외부 API 실패·쿼터 초과 시 서비스 레이어에서 폴백.

### GET /api/places

카카오 로컬 keyword 검색 상위 5건(MAP-02).

**Query Params:** `query`(필수), `lat`, `lng`(중심 좌표, 선택)

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": [
    { "placeId": "12345", "name": "성산일출봉", "address": "제주 서귀포시 성산읍", "lat": 33.4581, "lng": 126.9425, "category": "관광명소" }
  ]
}
```

### GET /api/places/address

coord2address 역지오코딩(MAP-04 핀 지정).

**Query Params:** `lat`(필수), `lng`(필수)

**Response `200`:** `result: { address, roadAddress }`

### GET /api/transit/route

길찾기 소요 시간·요금(BLK-04). **mode는 클라이언트가 확정해 전송** — 서버는 차량 이용 상태를 판정하지 않음.

**Query Params:**

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `sx` / `sy` | Y | 출발 경도/위도 |
| `ex` / `ey` | Y | 도착 경도/위도 |
| `mode` | Y | `BUS`\|`SUBWAY`\|`WALK`\|`TAXI`\|`CAR` |

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "durationMin": 42, "fare": 1400, "intervalMin": 13, "estimated": false, "fareConfidence": "CONFIRMED" }
}
```
> `intervalMin`은 BUS/SUBWAY 전용 확장 필드로 ODsay의 `totalIntervalTime`(환승 구간 배차간격 합, 분)이며 WALK/TAXI/CAR에는 없다(향후 그 모드들이 붙을 때 `null`).
> - `BUS`/`SUBWAY`: ODsay. 실패·경로없음 시 하버사인 추정 없이 즉시 실패(대중교통은 정류장 배치·환승 구조상 직선거리 추정이 부정확해 의미 없다고 판단) — `502`(API 실패) 또는 `404`(경로 없음)를 반환하며 프론트는 곧장 수동 입력으로 전환한다.
> - `CAR`: v1은 하버사인 × 평균속도(시내 30·시외 60km/h) 산식, `estimated: true`, 비용 수동(`fareConfidence: ESTIMATE`).
> - `TAXI`: 서버 산식(기본 4,800원 + km당 1,000원), `fareConfidence: ESTIMATE`.
> **요금 신뢰도**: 지하철·버스·기차 → `CONFIRMED`, 항공·택시·자차 → `ESTIMATE`. UI는 ESTIMATE에 "약 ~원 (변동 가능)" + "확정 예약 전 실제 사이트에서 확인" 안내.

### GET /api/trains

TAGO KTX 시간표 → 출발 후보 목록(앞 일정 종료 + 45분 버퍼 이후).

**Query Params:** `dep`(출발역), `arr`(도착역), `after`(기준 시각)

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": [
    { "trainType": "KTX", "depTime": "09:07", "arrTime": "11:52", "fare": 59900 }
  ]
}
```
> 실패 시 수동 입력 안내.

### GET /api/stations

역명 정적 목록 자동완성. **Query Params:** `query`. **Response:** `result: ["서울역", "서대구역", ...]`(서버 내장 데이터).

---

## 상세 명세 — 챗봇

### POST /api/projects/{projectId}/keywords

키워드 저장 + 후보 블록 자동 생성(BOT-02). 재입력 가능 — 재생성 시 기존 후보 블록은 그룹 자산이므로 유지하고 신규 생성분을 추가. Redis 분산 락으로 동시 중복 실행 방지. 생성 결과는 `BLOCK_CREATED` op 다건 브로드캐스트.

**Request Body:**
```json
{ "keywords": ["오름", "카페", "흑돼지"] }
```
> 최대 5개.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "후보 블록이 생성되었습니다.",
  "result": { "createdBlockIds": [201, 202, 203] }
}
```

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | keywords 5개 초과 또는 빈 배열 |
| `AI_SERVER_ERROR` | 500 | LLM 파이프라인 실패 |

### POST /api/projects/{projectId}/bot/chat

대화형 추천(BOT-03). 후보는 서버가 곧바로 후보 블록으로 생성(BOT-04). 4초 초과 대비 프론트 로딩 표시.

**Request Body:**
```json
{ "message": "비 오는 날 실내에서 갈 만한 곳 추천해줘", "mapContext": { "lat": 33.49, "lng": 126.53 } }
```

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "reply": "비 오는 날에는 이런 실내 명소를 추천해요.",
    "candidates": [
      { "blockId": 210, "name": "제주 아쿠아플라넷", "lat": 33.4310, "lng": 126.9280 }
    ]
  }
}
```
> LLM 파이프라인: 질의+컨텍스트(키워드·기간·현재 체인 요약) → 장소 후보 명사 추출 → **카카오 로컬 검색으로 실좌표 검증** → 검증 통과 건만 블록 생성(환각 좌표 차단).

---

## WebSocket / STOMP

REST로 변경 요청을 보내면, 서버가 seq를 붙여 STOMP로 전파한다. 프레즌스·커서는 seq 없이 별도 토픽(휘발성이라 재전송 대상 제외).

### 채널

| 채널 | 방향 | 용도 |
|---|---|---|
| `CONNECT` (헤더 `Authorization`) | C→S | JWT 검증, 세션에 memberId 바인딩 |
| `SUBSCRIBE` | C→S | **destination 인가** — projectId 파싱 후 그룹 멤버십 검증, 실패 시 프레임 거부 |
| SUB `/topic/project/{id}` | S→C | 블록·프로젝트·멤버 변경 op (seq 포함) |
| SUB `/topic/project/{id}/presence` | S→C | 접속/이탈/편집 배지(=텍스트 락 상태) |
| SUB `/topic/project/{id}/cursor` | S→C | 라이브 커서 |
| SEND `/app/project/{id}/cursor` | C→S | `{x, y, dayNo}` 50ms 스로틀 — DB 미저장, 릴레이 |
| SEND `/app/project/{id}/voice/signal` | C→S | WebRTC 시그널링 `{type: OFFER\|ANSWER\|ICE, targetMemberId, payload}` |
| SUB `/user/queue/voice` | S→C | 시그널 개인 수신(`convertAndSendToUser`) |

> **SUBSCRIBE 인가(필수 보안 요건)**: CONNECT의 JWT 검증은 "로그인 사용자"까지만 거른다. `ChannelInterceptor`에서 SUBSCRIBE·`/app` SEND destination의 projectId를 파싱 → 그룹 멤버십 검증 → 실패 시 거부. 통과한 projectId는 세션 어트리뷰트에 캐시(고빈도 프레임 DB 조회 회피).
> **탈퇴 시 세션 강제 종료**: memberId→WS 세션 레지스트리를 유지하고, 탈퇴 처리 시 해당 세션을 disconnect + 캐시 무효화.

### op 포맷

```json
{
  "seq": 1042,
  "type": "BLOCK_FIELD_UPDATED",
  "actorId": 3,
  "clientId": "uuid",
  "payload": { "blockId": 77, "fields": { "budget": 15000 }, "fieldUpdatedAt": { "budget": "..." } }
}
```

**op type 목록:**
`BLOCK_CREATED` / `BLOCK_FIELD_UPDATED` / `BLOCK_MOVED` / `BLOCK_DELETED` / `PROJECT_UPDATED` / `PROJECT_STATUS_CHANGED` / `PROJECT_DELETED` / `BUDGET_HEADCOUNT_CHANGED` / `MEMBER_JOINED` / `MEMBER_LEFT`

- `PROJECT_UPDATED`: 기간 축소 시 `movedToPool: [blockId...]` 포함.
- `PROJECT_DELETED`: 대시보드를 보던 멤버를 그룹 페이지로 리다이렉트.
- `MEMBER_LEFT`: 멤버 탈퇴 시 나머지 멤버는 이 op로 멤버 목록·정산 인원 갱신.

**순서 보장**: 서버는 프로젝트 단위 락으로 채번~전송을 직렬화하고, 클라이언트는 수신 seq에 갭이 생기면 `GET /api/projects/{id}/ops?afterSeq`로 메꾼다(이중 방어).
