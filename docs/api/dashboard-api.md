# 대시보드 API

> `/projects/{projectId}`, `/blocks`, `/places`, `/transit`, `/trains`, WebSocket — 프로젝트 보드(실시간 협업)

프로젝트 대시보드는 블록 편집·지도·예산·챗봇·보이스가 결합된 실시간 협업 보드다. **변경 요청은 REST, 전파는 STOMP 브로드캐스트**로 처리한다(서버 권위 모델: 검증→DB→seq op 브로드캐스트).

---

## 공통 규약

- **응답 래퍼 `CustomResponse`**: `{ "isSuccess", "code", "message", "result" }`. (인증API.md 참조)
- **인증 헤더**: 모든 REST 엔드포인트 `Authorization: Bearer {accessToken}` 필요. WS는 CONNECT 헤더로 검증.
- **멤버십 검증**: 프로젝트가 속한 그룹의 멤버만 접근(REST AOP `@GroupMember` + WS `ChannelInterceptor`). 비멤버는 `403`(REST) / 프레임 거부(WS).
- **X-Client-Id**: 변경 요청 헤더에 브라우저 탭 UUID를 실어 보낸다. 서버는 이 값을 op에 그대로 실어 브로드캐스트하며(수신자 제외 없이 전원 발송), **자기 op를 어떻게 처리할지는 클라이언트가 op 종류별로 판단한다** — 아래 "자기 op 에코 정책" 참조.
- **범위 구분**: 프로젝트 카드 목록·생성·이름/기간 수정·삭제는 [개인·그룹 페이지API.md](개인·그룹%20페이지API.md) 참조.

---

## 엔드포인트 목록

### 프로젝트 보드

| Method | Path | 설명 | Auth |
|---|---|---|---|
| GET | `/api/projects/{projectId}` | 대시보드 스냅샷 (최초 로딩·재연결 재로딩) | Yes |
| GET | `/api/projects/{projectId}/ops?afterSeq={n}` | 유실 op 재전송 (재연결·seq 갭) | Yes |
| PATCH | `/api/projects/{projectId}/status` | `PLANNING↔DONE` 양방향 전환 | Yes |
| PATCH | `/api/projects/{projectId}/budget` | 프로젝트 목표 예산(총액) 변경 | Yes |
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
| POST | `/api/projects/{projectId}/chatbot/messages` | 대화형 채팅 — 일반/지도기반 추천 통합 | Yes |

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
| `GROUP403` | 403 | 그룹 멤버가 아님 |
| `PROJECT404` | 404 | 프로젝트 없음/삭제됨 |

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
| `COMMON400_1` | 400 | status가 PLANNING/DONE 이외 |

---

### PATCH /api/projects/{projectId}/budget

프로젝트 전체 목표 예산 변경(BGT-02). `TARGET_BUDGET_CHANGED` op 브로드캐스트.

**Request Body:**
```json
{ "targetBudget": 100000 }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `targetBudget` | int | N | 목표 예산(총액, 0 이상). **null이면 예산 미설정으로 초기화** — 값 누락이 아니라 명시적 의도로 취급한다 |

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "목표 예산이 변경되었습니다.",
  "result": { "targetBudget": 100000 }
}
```

> 블록의 `budget`(실제 지출 항목)과 다른 값이다. 이쪽은 "얼마까지 쓸 것인가"이고, 블록 `budget` 합계가 "얼마를 쓰기로 했는가"다.

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

> 모든 성공 변경은 서버가 seq를 붙여 `/topic/project/{id}`로 브로드캐스트한다. 요청자 본인에게도 발송되며, 처리 방식은 "자기 op 에코 정책"을 따른다.

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
| `BLOCK400` | 400 | 장소성 카테고리 lat/lng 누락 |
| `BLOCK400_1` | 400 | vehicleFlag를 ETC 외 카테고리에 지정 |
| `GROUP403` | 403 | 그룹 멤버가 아님 |

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
| `BLOCK410` | 410 | 이미 삭제된 블록에 대한 지연 op |

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

ODsay 열차 시간표(KTX·무궁화 등) → 출발 후보 목록(앞 일정 종료 + 45분 버퍼 이후).

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
> 실패 시 수동 입력 안내. (`domain.transit`의 `TransitScheduleQueryService.getTrainSchedule`이 이 조회를 이미 제공 — 이 엔드포인트 자체는 아직 컨트롤러 미구현, 위 시각/버퍼 계산 로직만 남음.)

### GET /api/stations

역/터미널 이름검색 — ODsay 라이브 프록시(정적 목록 아님). **Query Params:** `type`(`TRAIN`\|`EXPRESS_BUS`\|`INTERCITY_BUS`), `query`(역/터미널 이름 일부, 부분일치). **Response:** `result: { stationId, stationName, lat, lng, destinations: [{stationId, stationName, lat, lng}, ...] }`(검색된 역 자체 정보 + 그 역에서 갈 수 있는 목적지 목록). `domain.transit`의 `TransitScheduleQueryService.searchTrainStation`/`searchExpressBusTerminal`/`searchIntercityBusTerminal`이 이미 제공 — 이 엔드포인트 자체는 아직 컨트롤러 미구현.

---

## 상세 명세 — 챗봇

### POST /api/projects/{projectId}/chatbot/messages

대화형 채팅 엔드포인트 하나로 일반 채팅과 지도 기반 추천을 모두 처리한다(BOT-01~04). 모드는 `mode` 필드로 **명시적으로** 받는다 — `mapContext` 유무로 암묵 추론하지 않는다(지도가 대시보드에 항상 떠 있어 좌표값 자체는 모드와 무관하게 존재할 수 있으므로, 존재 여부를 모드 판단 근거로 쓰면 오판 소지가 있다).

**GENERAL 모드**: 지도 비연동. LLM이 메시지를 분석해 필요한 도구(카카오 키워드 검색·TAGO 시간표 등)를 판단해 호출하고 텍스트로 응답한다. 키워드 기반 후보 생성(BOT-02)도 이 모드 안에서 하나의 도구 호출로 처리되며 별도 엔드포인트가 없다 — 사용자는 그냥 문장으로 입력하고("오름이랑 카페 갈 만한 데 추천해줘"), 백엔드/LLM이 파싱해 프로젝트 `keywords`에 저장한다. 도구를 안 쓰면 `candidates`는 빈 배열.

가게 영업여부·폐업·리뷰처럼 학습 지식만으로 답할 수 없는 실시간 정보는 Anthropic `web_search` 서버 도구로 실검색해 요약 응답한다. 남용·비용 방지를 위해 시스템 프롬프트와 `max_uses`로 용도를 실시간 확인이 필요한 정보에 한정한다(날씨·환율처럼 검색으로도 신뢰하기 어려운 값은 제외). Spring AI 1.1.8이 서버 도구 선언 API를 제공하지 않아, `AnthropicApi`의 RestClient 인터셉터로 요청에 도구를 주입하고 응답의 검색 결과 블록을 정규화한다.

**MAP 모드**: `mapContext`(현재 지도 뷰포트 좌표, 추후 확장 가능) 필수. 서버가 먼저 카카오 API로 해당 범위 장소를 조회하는 고정 파이프라인(LLM이 검색 여부를 판단하지 않는다) → 조회 결과 + 메시지를 LLM에 넘겨 N개 선별·추천 이유 생성 → `candidates`에 블록화 가능한 구조로 반환.

공통: 후보는 **카카오 로컬 검색으로 실좌표 검증**된 것만 사용(LLM 환각 좌표 차단), 서버가 곧바로 후보 블록으로 생성(BOT-04). 4초 초과 대비 프론트 로딩 표시.

**Request Body:**
```json
{
  "message": "비 오는 날 실내에서 갈 만한 곳 추천해줘",
  "mode": "MAP",
  "mapContext": { "lat": 33.49, "lng": 126.53 }
}
```
> `mode`: `GENERAL` | `MAP`. `mapContext`는 `mode: MAP`일 때만 필수.

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
> GENERAL 모드에서 도구 미사용 시 `candidates: []`.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | `message` 공백/2000자 초과, `mode: MAP`인데 `mapContext` 누락 |
| `CHATBOT502` | 502 | GMS/LLM 호출 실패 |
| `CHATBOT500` | 500 | 대화 이력 저장 실패 |

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
`BLOCK_CREATED` / `BLOCK_FIELD_UPDATED` / `BLOCK_MOVED` / `BLOCK_DELETED` / `PROJECT_UPDATED` / `PROJECT_STATUS_CHANGED` / `PROJECT_DELETED` / `TARGET_BUDGET_CHANGED` / `BUDGET_HEADCOUNT_CHANGED` / `MEMBER_JOINED` / `MEMBER_LEFT`

- `PROJECT_UPDATED`: 기간 축소 시 `movedToPool: [blockId...]` 포함.
- `PROJECT_DELETED`: 대시보드를 보던 멤버를 그룹 페이지로 리다이렉트.
- `MEMBER_LEFT`: 멤버 탈퇴 시 나머지 멤버는 이 op로 멤버 목록·정산 인원 갱신.

**순서 보장**: 서버는 프로젝트 단위 락으로 채번~전송을 직렬화하고, 클라이언트는 수신 seq에 갭이 생기면 `GET /api/projects/{id}/ops?afterSeq`로 메꾼다(이중 방어).

### 자기 op 에코 정책

서버는 op를 **구독자 전원에게** 보낸다 — 요청자 본인을 서버가 걸러내지 않는다. 따라서 자기 `clientId`가 실린 op를 어떻게 처리할지는 클라이언트가 결정하며, **op 종류에 따라 다르다.**

| op | 자기 op 처리 | 이유 |
|---|---|---|
| `BLOCK_CREATED` / `BLOCK_DELETED` | **스킵** | 낙관적 UI가 이미 추가·제거를 반영했고, 재적용하면 중복 생성·이중 삭제가 된다 |
| `BLOCK_MOVED` / `BLOCK_FIELD_UPDATED` | **재적용(에코)** | 서버가 확정한 값으로 덮어써야 한다. 스킵하면 낙관적으로 그린 위치·값이 서버 상태와 어긋난 채 남는다 |

**왜 일괄 스킵이 아닌가**: 처음에는 `clientId`로 자기 op를 전부 스킵하는 설계였으나, 동시 드래그 상황에서 보드가 발산하는 버그가 발생했다. 두 사람이 같은 Day의 블록을 연달아 옮기면 각자 자기 이동은 낙관적 값으로만 남고 서버가 정한 최종 순서를 받지 못해, 두 화면이 서로 다른 순서로 굳는다. 이동·필드 갱신만 에코를 허용해 서버 값으로 수렴시키는 것으로 해결했다.

근거와 재현 경로는 `docs/realtime-sync-policy.md` 참조.

> 낙관적 UI를 쓰는 클라이언트는 이 표를 그대로 구현해야 한다. "자기 op는 무시"라고 단순화하면 위 발산 버그가 재발한다.
