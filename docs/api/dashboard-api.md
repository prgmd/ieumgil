# 대시보드 API

> `/projects/{projectId}`, `/blocks`, `/places`, `/transit`, `/trains`, WebSocket — 프로젝트 보드(실시간 협업)

프로젝트 대시보드는 블록 편집·지도·예산·챗봇·보이스가 결합된 실시간 협업 보드다. **변경 요청은 REST, 전파는 STOMP 브로드캐스트**로 처리한다(서버 권위 모델: 검증→DB→seq op 브로드캐스트).

---

## 공통 규약

- **응답 래퍼 `CustomResponse`**: `{ "isSuccess", "code", "message", "result" }`. ([auth-api.md](auth-api.md) 참조)
- **인증 헤더**: 모든 REST 엔드포인트 `Authorization: Bearer {accessToken}` 필요. WS는 CONNECT 헤더로 검증.
- **멤버십 검증**: 프로젝트가 속한 그룹의 멤버만 접근(REST AOP `@GroupMember` + WS `ChannelInterceptor`). 비멤버는 `403`(REST) / 프레임 거부(WS).
- **X-Client-Id**: 변경 요청 헤더에 브라우저 탭 UUID를 실어 보낸다. 서버는 이 값을 op에 그대로 실어 브로드캐스트하며(수신자 제외 없이 전원 발송), **자기 op를 어떻게 처리할지는 클라이언트가 op 종류별로 판단한다** — 아래 "자기 op 에코 정책" 참조.
- **범위 구분**: 프로젝트 카드 목록·생성·이름/기간 수정·삭제는 [my-group-api.md](my-group-api.md) 참조.

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
| POST | `/api/projects/{projectId}/transit-candidates` | 체인 구간별 교통수단 후보 일괄 계산 | Yes |
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
| GET | `/api/stations?type=&query=` | 역/터미널 이름검색 | Yes |

### 챗봇

| Method | Path | 설명 | Auth |
|---|---|---|---|
| POST | `/api/projects/{projectId}/chatbot/messages` | 대화형 채팅 — 일반/지도기반 추천 통합 | Yes |
| GET | `/api/projects/{projectId}/chatbot/messages` | 대화 이력 조회 — 추천 후보 포함, 새로고침 복원용 | Yes |

### 축제

| Method | Path | 설명 | Auth |
|---|---|---|---|
| GET | `/api/festivals/{contentId}/homepage` | 축제 공식 홈페이지 URL 조회 | Yes |

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
  "transportMeta": null,
  "detail": "개최 기간: 2026-10-04 ~ 2026-10-14"
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
| `detail` | string | N | 세부 내용, 500자 이하. 생성 시점에 아는 정보를 담는다(예: 챗봇 축제 후보의 개최 기간). 이후 편집은 detail-lock이 걸린 LWW 갱신 경로를 쓴다 |

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

## 상세 명세 — 교통 후보

### POST /api/projects/{projectId}/transit-candidates

체인 순서의 블록 id 목록을 받아 연속 구간마다 이동수단 후보를 계산한다(BLK-04/BLK-10). **블록을 생성하지 않는다** — 순수 계산 결과만 반환하며, 사용자가 후보 중 하나를 고르면 프론트가 별도로 `POST /api/projects/{projectId}/blocks`를 호출해야 실제 교통 블록이 생긴다(아래 매핑표 참조).

계산은 두 단계다. 1단은 모든 구간의 시내 경로·자차·택시 조회를 병렬로 모으고(최대 20초) — 시외 여부(`intercity`)도 이때 ODsay가 준 경로의 `pathType`(11 기차·12 고속버스·13 항공·14 해운·20 복합 중 하나라도 있으면 시외)으로 판정된다. 2단은 구간을 순서대로 훑으며 **Day 안에서 처음 만나는 시외 구간 하나에만** 기차·고속버스·항공 시간표를 붙인다(최대 20초, 1단 다음 순차라 최악 40초). 뒤따르는 시외 구간과 여행 날짜를 모르는 구간은 시간표를 건너뛴다 — 아래 "시간표를 적용하지 않는 경우" 참조.

**Request Body:**
```json
{ "blockIds": [101, 105, 107], "dayStart": "09:00" }
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `blockIds` | array\<long\> | Y | 구간을 만들 블록 id, 체인 순서대로. 최대 30개(초과 시 `400`) |
| `dayStart` | string(`HH:mm`) | N | Day 시작 시각. 미지정 시 `09:00` |

> 서버가 인접 쌍으로 구간을 만든다 — `[101,105,107]`이면 `(101,105)`,`(105,107)` 두 구간.
> `blockIds`가 0~1개면 만들 구간이 없으므로 **`segments: []`를 200으로 반환한다(400이 아니다)** — 단, 그 1개가 이 프로젝트에 실재하고 좌표를 가진 유효한 블록일 때다. 구간 생성 전에 요청에 포함된 **모든** 블록의 존재·좌표를 먼저 검사하므로, blockIds가 1개뿐이라도 그 블록이 없거나 좌표가 없으면 여전히 `400`이다.
> 같은 id가 연속으로 오면(예: `[101,101,105]`) 그 쌍만 건너뛴다 — 이동이 없다고 보고 구간을 만들지 않는다. 단, 이 경우도 좌표 검사는 두 id 모두에 대해 먼저 이뤄진다(예: `[101,101]`에서 101에 좌표가 없으면 구간이 0개여도 `TRANSIT400_2`).
> `dayStart`는 날짜를 받지 않는다 — 여행 날짜는 프로젝트 `startDate`와 블록 `dayNo`(`startDate + (dayNo-1)`)로 서버가 유도하며, `startDate`가 없으면 그 Day의 시외 구간은 시간표를 아예 적용하지 못한다(아래 참조).
> 여러 Day를 한 요청에 담아도 `dayStart`는 하나뿐이며 모든 Day에 동일하게 적용된다(Day별 시작 시각은 아직 표현할 수 없다).

**Response `200`(시내 구간 — 다중 후보 + 환승 상세):**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "segments": [
      {
        "fromBlockId": 101,
        "toBlockId": 105,
        "intercity": false,
        "timetableApplied": false,
        "timetableSkipReason": null,
        "referenceAt": null,
        "defaultMode": "TRANSIT",
        "candidates": [
          {
            "mode": "TRANSIT", "label": "대중교통", "available": true,
            "durationMin": 44, "fare": 1500, "fareConfidence": "CONFIRMED",
            "intervalMin": 9, "distanceM": 12841, "transferCount": 0, "walkMeters": 150,
            "labels": ["추천", "환승 최소", "도보 최소"], "caution": null,
            "legs": [{ "type": "BUS", "lineName": null, "from": "시청", "to": "강남역", "durationMin": 44 }],
            "departures": null
          },
          {
            "mode": "TRANSIT", "label": "대중교통", "available": true,
            "durationMin": 28, "fare": 1600, "fareConfidence": "CONFIRMED",
            "intervalMin": 4, "distanceM": 11200, "transferCount": 1, "walkMeters": 500,
            "labels": ["최단 시간"], "caution": null,
            "legs": [{ "type": "SUBWAY", "lineName": null, "from": "시청", "to": "강남역", "durationMin": 28 }],
            "departures": null
          },
          {
            "mode": "TRANSIT", "label": "대중교통", "available": true,
            "durationMin": 51, "fare": 1200, "fareConfidence": "CONFIRMED",
            "intervalMin": 12, "distanceM": 12900, "transferCount": 2, "walkMeters": 900,
            "labels": ["최저 요금"], "caution": null,
            "legs": [{ "type": "BUS", "lineName": null, "from": "시청", "to": "역삼", "durationMin": 51 }],
            "departures": null
          },
          {
            "mode": "TAXI", "label": "택시", "available": true,
            "durationMin": 32, "fare": 14900, "fareConfidence": "CONFIRMED",
            "intervalMin": null, "distanceM": 10327, "transferCount": null, "walkMeters": null,
            "labels": null, "caution": null, "legs": null, "departures": null
          },
          {
            "mode": "WALK", "label": "도보", "available": false,
            "durationMin": null, "fare": null, "fareConfidence": null,
            "intervalMin": null, "distanceM": null, "transferCount": null, "walkMeters": null,
            "labels": null, "caution": null, "legs": null, "departures": null
          }
        ]
      }
    ]
  }
}
```

`legs[].lineName`은 위 예시처럼 ODsay가 노선 정보를 안 줄 때 `null`이다. 채워질 때는 지하철은 노선명("2호선"), 버스는 번호("402")가 온다 — 도보 구간은 ODsay가 승하차 지점 자체를 주지 않아 `from`/`to`까지 `null`이다.

**Response `200`(시외 구간 — 수단 분리 + 출발편):**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "segments": [
      {
        "fromBlockId": 105,
        "toBlockId": 107,
        "intercity": true,
        "timetableApplied": true,
        "timetableSkipReason": null,
        "referenceAt": "14:45",
        "defaultMode": "TRAIN",
        "candidates": [
          {
            "mode": "TRAIN", "label": "기차", "available": true,
            "durationMin": 157, "fare": 59800, "fareConfidence": "CONFIRMED",
            "intervalMin": null, "distanceM": null, "transferCount": 0, "walkMeters": null,
            "labels": null, "caution": null,
            "legs": [{ "type": "TRAIN", "lineName": null, "from": "서울", "to": "부산", "durationMin": 157 }],
            "departures": [
              { "name": "KTX 1", "grade": "KTX", "departureAt": "16:00", "arrivalAt": "18:37", "durationMin": 157, "fare": 59800, "fareConfidence": "CONFIRMED", "fareOptions": { "general": 59800, "special": 99800, "standing": 54800 }, "labels": [] },
              { "name": "KTX 15", "grade": "KTX", "departureAt": "16:30", "arrivalAt": "19:12", "durationMin": 162, "fare": 59800, "fareConfidence": "CONFIRMED", "fareOptions": { "general": 59800, "special": 99800, "standing": 54800 }, "labels": [] },
              { "name": "무궁화 1203", "grade": "무궁화", "departureAt": "18:10", "arrivalAt": "23:41", "durationMin": 331, "fare": 28600, "fareConfidence": "CONFIRMED", "fareOptions": { "general": 28600, "special": 68600, "standing": 23600 }, "labels": ["최저 요금"] }
            ]
          },
          {
            "mode": "EXPRESS_BUS", "label": "고속·시외버스", "available": true,
            "durationMin": 240, "fare": 39700, "fareConfidence": "CONFIRMED",
            "intervalMin": null, "distanceM": null, "transferCount": 0, "walkMeters": null,
            "labels": null, "caution": null,
            "legs": [{ "type": "EXPRESS_BUS", "lineName": null, "from": "서울", "to": "부산", "durationMin": 240 }],
            "departures": [
              { "name": "고속버스", "grade": "우등", "departureAt": "16:00", "arrivalAt": "20:00", "durationMin": 240, "fare": 39700, "fareConfidence": "CONFIRMED", "fareOptions": null, "labels": [] }
            ]
          },
          {
            "mode": "AIR", "label": "항공", "available": true,
            "durationMin": 55, "fare": null, "fareConfidence": "UNKNOWN",
            "intervalMin": null, "distanceM": null, "transferCount": 0, "walkMeters": null,
            "labels": null, "caution": null,
            "legs": [{ "type": "AIR", "lineName": null, "from": "김포", "to": "김해", "durationMin": 55 }],
            "departures": [
              { "name": "대한항공 1201", "grade": "대한항공", "departureAt": "17:20", "arrivalAt": "18:15", "durationMin": 55, "fare": null, "fareConfidence": "UNKNOWN", "fareOptions": null, "labels": [] }
            ]
          }
        ]
      }
    ]
  }
}
```

> 항공 편명·시각은 예시다 — 확실한 사실은 `fare`가 항상 `null`이고 `fareConfidence`가 항상 `UNKNOWN`이라는 것뿐이다(ODsay 항공 시간표에 요금 필드 자체가 없어 추정하지 않는다). `TAXI`·`CAR` 후보도 이 구간에 함께 온다 — 시내·시외를 구분하지 않고 같은 방식(카카오 길찾기)으로 계산되며, 지면상 위 예시에서는 생략했다.

**Segment 필드:**

| 필드 | 타입 | 설명 |
|---|---|---|
| `segments[].fromBlockId` / `toBlockId` | long | 구간의 출발/도착 블록 id |
| `segments[].intercity` | bool | 시외 구간 여부(ODsay `pathType` 기준, 위 참조) |
| `segments[].timetableApplied` | bool | 시간표를 조회해 실제 출발편을 골랐는지. `intercity:false`(시내)면 해당 없음으로 항상 `false`다 |
| `segments[].timetableSkipReason` | string \| null | `timetableApplied:false`인 이유. 시내처럼 애초에 해당 없는 경우는 `null`(사유 없음과 미적용을 구분하지 않는다) |
| `segments[].referenceAt` | string(`HH:mm`) \| null | 이 구간의 출발편을 고르는 기준 시각. 시간표를 적용한 시외 구간에만 있다 |
| `segments[].defaultMode` | enum \| null | 후보 중 기본값 — 조회에 성공했고(`available:true`) **실제로 탈 편이 있는** 첫 후보(우선순위순: `TransportPref`가 정한 수단 또는 `TRAIN`→`EXPRESS_BUS`→`AIR`). 모든 후보가 조회 실패했거나, 시외에서 고를 편이 있는 수단이 하나도 없으면 `null` |
| `segments[].candidates` | array | 아래 참조 |

**Candidate 필드:**

| 필드 | 타입 | 설명 |
|---|---|---|
| `mode` | enum | `TransitMode` 값(아래 표) |
| `label` | string | 한글 표시명 |
| `available` | bool | **조회 실패 여부만** 나타낸다. 거리 임계로 제외된 도보처럼 애초에 안 부른 수단은 `candidates`에 없다(아래 콜아웃) |
| `durationMin` / `fare` / `fareConfidence` / `intervalMin` / `distanceM` / `labels` / `transferCount` / `walkMeters` / `caution` / `legs` / `departures` | - | 수단별로 채워지는 필드가 다르다 — 아래 표 참조 |

**수단마다 채워지는 필드가 다르다** (`available:true`인데도 `null`인 필드는 프론트가 그 항목만 숨기면 된다)

| 필드 | `TRANSIT` | `TRAIN` | `EXPRESS_BUS` | `AIR` | `TAXI` | `CAR` | `WALK` |
|---|---|---|---|---|---|---|---|
| `durationMin` | ○ | ○\* | ○\* | ○\* | ○ | ○ | ○ |
| `fare` | ○ | ○\* | ○\* | **null** | ○ | ○ | ○(항상 `0`) |
| `intervalMin` | ○ | null | null | null | null | null | null |
| `distanceM` | ○ | **null** | **null** | **null** | ○ | ○ | ○ |
| `transferCount` | ○ | `0` | `0` | `0` | null | null | null |
| `walkMeters` | ○ | null | null | null | null | null | null |
| `labels`(Candidate 레벨) | ○ | null | null | null | null | null | null |
| `caution` | null | null | null | null | null | null | null |
| `legs` | ○ | ○\*(0~1개) | ○\*(0~1개) | ○\*(0~1개) | null | null | null |
| `departures` | null | ○(0~3개) | ○(0~3개) | ○(0~3개) | null | null | null |

\* `TRAIN`/`EXPRESS_BUS`/`AIR`는 "편은 있는데 아직 못 고른 것"이 아니라 **후보 자체가 슬롯**이다 — 시간표 조회에 성공했지만 기준 시각 이후 탈 편이 없으면(또는 애초에 시간표를 적용하지 않으면) `available:true`인 채로 `departures: []`·`legs: []`·`durationMin`/`fare`: `null`을 낸다("모드 슬롯"과 "실제 제안"을 구분하지 않으면 사라진 수단이 "배로만 가라"는 뜻이 된다). `durationMin`/`fare`는 **편이 있어도** `null`일 수 있다 — 대표값은 항상 선택된 첫 편에서 오는데, 그 편 자체의 소요시간·요금을 ODsay가 안 줄 때가 있다(예: 고속버스 시간표가 `wasteTime`·`fare`를 비워서 주는 편). 지어내지 않고 그대로 `null`로 낸다. `distanceM`은 세 수단 모두 시간표 API가 거리를 주지 않아 편이 있어도 항상 `null`이다.

**`TransitMode`:**

| 값 | 한글 | 비고 |
|---|---|---|
| `TRANSIT` | 대중교통 | 시내 버스+지하철 통합 조회(ODsay). "대중교통이냐 택시냐"가 선택 단위라 버스·지하철을 따로 내지 않는다. 최대 5개 후보(아래 참조) |
| `TRAIN` | 기차 | ODsay 기차 시간표. 시외 구간에서만 나온다 |
| `EXPRESS_BUS` | 고속·시외버스 | ODsay 고속/시외버스 시간표. 시외 구간에서만 나온다 |
| `AIR` | 항공 | ODsay 항공 시간표. 시외 구간에서만 나오며 요금은 항상 `null` |
| `TAXI` | 택시 | 카카오모빌리티 Directions API 실측 요금. 시내·시외 구분 없이 나온다 |
| `CAR` | 자차 | 통행료(카카오 실측) + 연료비(추정) 합산. 시내·시외 구분 없이 나온다 |
| `WALK` | 도보 | 카카오 도보 길찾기 API. 시외 구간에는 나오지 않는다(직선거리 임계 이전에 시외로 갈라진다). **요금 자체가 없어 `fare`는 항상 `0`**(하드코딩, 실측값이 아니다) |

**`Leg`(경로 구간 상세):**

| 필드 | 타입 | 설명 |
|---|---|---|
| `type` | enum | `SUBWAY`\|`BUS`\|`WALK`\|`TRAIN`\|`EXPRESS_BUS`\|`AIR`\|`FERRY`\|`OTHER`. `FERRY`는 도서 목적지 판정에만 내부적으로 쓰이고 현재 응답의 `legs`에는 나타나지 않는다(해운이 섞인 경로는 시내 선정기에 닿지 않고, 시외 후보의 leg는 수단 고정 값을 쓴다) |
| `lineName` | string \| null | 지하철 노선명·버스 번호·항공사명. 정보가 없거나(도보) 시외 후보의 leg(수단 고정 매핑이라 애초에 채우지 않는다)면 `null` |
| `from` / `to` | string \| null | 승하차 지점명. 도보 구간은 ODsay가 지점을 주지 않아 `null` |
| `durationMin` | int | 정수 필드라 `null`이 없다 — 모르면 `0`이다 |

**`Departure`(시외 출발편):**

| 필드 | 타입 | 설명 |
|---|---|---|
| `name` | string | 사용자에게 보일 이름(`"KTX 1"`·`"무궁화 1203"`·`"고속버스"`·`"{항공사} {편명}"`) |
| `grade` | string \| null | 등급/항공사(`"KTX"`·`"무궁화"`·`"일반"`\|`"우등"`\|`"프리미엄"`\|항공사명). **JSON 키는 예약어인 `class`가 아니라 `grade`다.** 고속버스 등급 코드를 해석 못 하면 `null` |
| `departureAt` / `arrivalAt` | string(`HH:mm`) \| null | 고속버스는 시간표에 도착 시각이 없어 `출발 + 소요`로 계산한다. 소요시간마저 없으면 `arrivalAt`도 `null`이다(도착 시각을 지어내지 않는다) |
| `durationMin` | int \| null | 시간표가 주지 않으면 `null`이다(`0`으로 두면 즉시 도착으로 읽힌다) |
| `fare` | int \| null | 항공은 항상 `null`이다(요금 정보 자체가 없다 — 성수기·항공사에 따라 배 이상 차이 나 추정하지 않는다) |
| `fareConfidence` | enum | `CONFIRMED`\|`UNKNOWN`(항공, 또는 고속버스 편에 요금이 없을 때) |
| `fareOptions` | object \| null | 등급별 요금(`general`/`special`/`standing`). **기차만** 있다 |
| `labels` | array\<string\> | `"최저 요금"` 등 이 편이 뽑힌 이유. 없으면 `[]`(`null`이 아니다) |

**후보·편 선정 규칙:**

- 시내 대중교통(`TRANSIT`)은 ODsay 경로 중 최대 5개다 — 추천(첫 경로)·최단 시간·최저 요금·환승 최소·도보 최소 다섯 축에서 하나씩 뽑되, 같은 경로가 여러 축에서 뽑히면 `labels`를 합쳐 하나로 낸다(그만큼 5개보다 적을 수 있다). 축이 겹쳐 5개가 안 차면 ODsay가 준 순서대로 나머지를 채운다.
- 시외 출발편(`departures`)은 기준 시각(`referenceAt`) 이후 편 중 최대 3개다. 기차·고속버스는 **시각순 2편 + 최저 요금(일반석 기준) 1편**이고, 항공은 요금 정보가 없어 **시각순 3편**이다. 기준 시각 이전 편은 제외한다.
- **운행 요일**: ODsay `runDay`(`"매일"`·`"토일"`·`"목"`·`"월수금"`·`"평일"`·`"주말"`·`"휴일"` 등 한글 표기)를 여행 날짜의 요일과 맞춰, 그날 운행하지 않는 편은 시간표 조회 단계에서 이미 제외한다. **공휴일은 평일로 취급**한다(공휴일 API를 따로 붙이지 않는다 — 요금이 조금 다를 수 있어도 그 불확실성을 노출하는 쪽이 더 혼란스럽다). 해석하지 못하는 표기는 걸러내지 않고 통과시킨다(편을 놓치는 것보다 여분의 후보를 보여주는 편이 낫다). **고속버스는 이 필터가 적용되지 않는다** — ODsay 고속버스 시간표 응답 자체에 `runDay`가 없다. 기차 요금은 여행 날짜의 요일(평일/주말)에 맞는 값이 있으면 그 값을 우선한다.

**기준 시각(`referenceAt`)과 45분 버퍼:** 서버가 Day 시작 시각(`dayStart`, 기본 `09:00`)부터 구간을 순서대로 훑으며 커서를 누적한다. 각 구간의 `referenceAt`은 그 시점 커서에 **역·터미널·공항 이동 버퍼 45분**(명세 BLK-04)을 더한 값이다. 커서는 앞 구간의 (기본 수단 `durationMin` + 도착 블록 체류시간)만큼만 전진하며 시외 구간의 실제 탑승 시각(예: KTX 16:00 출발)은 반영하지 않는다 — 그래서 커서는 뒤 구간으로 갈수록 신뢰할 수 없어지고, 그 때문에 시간표는 Day의 첫 시외 구간에만 적용된다.

**시간표를 적용하지 않는 경우(`timetableApplied: false`):**

| 상황 | `timetableSkipReason` | 비고 |
|---|---|---|
| 시내 구간(`intercity: false`) | `null` | 애초에 해당 없음 — 사유가 아니라 미적용이다 |
| 같은 Day에서 두 번째 이후의 시외 구간 | `"앞선 시외 구간의 편이 확정되지 않았습니다"` | Day당 시간표는 첫 시외 구간에서 한 번만 조회한다 |
| `project.startDate`가 없음 | `"프로젝트 시작일이 없어 운행 요일을 확인할 수 없습니다"` | 오늘 날짜로 갈음하지 않는다 — 실제 여행일과 무관한 시간표를 확정처럼 낼 수 없다. 이 경우 그 Day의 **모든** 시외 구간이 같은 사유를 받는다(뒤 구간에 "앞 구간 때문"이라는 잘못된 사유가 붙지 않도록 한다) |
| 시간표 조회 공유 예산 소진 | `"다른 Day의 시간표 조회가 시간을 다 써서 확인하지 못했습니다"` | 2단의 20초 상한은 요청 전체(모든 Day)가 공유한다 — 앞선 Day의 조회가 오래 걸려 예산을 다 썼으면 이 Day는 조회 자체를 시도하지 않는다. 마찬가지로 그 Day의 모든 시외 구간이 같은 사유를 받는다 |

세 스킵 케이스 모두 `referenceAt: null`이고, `TRAIN`/`EXPRESS_BUS`/`AIR` 세 수단 슬롯은 그대로 남되(`available: true`) `departures: []`·`legs: []`이며 `durationMin`/`fare`는 `null`이다 — 수단 자체를 지우면 "제주는 배로만 가라"는 뜻이 되므로, 조회를 안 한 것과 조회에 실패한 것을 구분한다. 프론트는 이 구간에서 시간표 기반 편 선택 UI 대신 `timetableSkipReason`을 그대로 안내 문구로 보여주면 된다.

**도서 목적지처럼 육로 경로가 없는 구간 — 자차·택시 후보가 아예 오지 않는다:** ODsay 대중교통 경로 목록 중 육로(항공·해운 leg가 없는 경로)로만 이어지는 대안이 **하나도 없으면**(제주 등 도서 목적지), 자차·택시 후보 자체를 만들지 않는다 — `candidates`에 `CAR`/`TAXI`가 아예 나타나지 않는다. 카카오 길찾기는 도로 주행만 계산해 성공을 반환할 수 있지만(청주→제주 실측: 도로 주행만으로 계산된 `distanceM` 436,642 · `durationMin` 356 · 택시 `fare` 556,600원), 배를 타야 하는 구간을 확정 요금처럼 내보내는 것은 사용자를 오도한다고 판단해 값 자체를 만들지 않기로 했다. `TRAIN`/`EXPRESS_BUS`/`AIR` 슬롯은 평소처럼 그대로 남는다(항공이 도서 목적지의 실질적 기본 수단이 된다).

경로 목록 조회 자체가 실패했을 때(타임아웃 등, `paths`가 비어 있음)는 "육로가 없다"가 아니라 "모른다"이므로 이 판정을 적용하지 않는다 — 자차·택시 후보를 지금까지처럼 정상적으로 만든다. 드라이빙 조회만 별도로 실패해 `available: false`인 후보는 이 판정과 무관하게 그대로 남는다.

`distanceM`은 **외부 API가 준 실제 경로 거리(미터)**이고 직선거리가 아니다. 대중교통(ODsay `totalDistance`)·자차·택시(카카오)·도보(카카오)에서 채워지며, 위 표처럼 시외 시간표 수단(`TRAIN`/`EXPRESS_BUS`/`AIR`)에는 채워지지 않는다(`null`). 같은 서울시청→강남역 구간에서 대중교통 `distanceM`은 12,841m, 택시 경로는 10,327m다(같은 두 지점이라도 대중교통과 도로 경로는 서로 다르고, 둘 다 직선거리 8,785m와도 다르다 — 대중교통·택시가 반드시 같은 도로를 타는 것도 아니다). 자차 연료비도 이 값으로 계산하므로 직선거리를 쓰면 기름값이 실제보다 낮게 나온다.

> 서버 내부에서 쓰는 직선거리(하버사인)는 300m·2km 임계를 판정해 **외부 API를 부를지 말지** 정하는 용도이며 응답에 나가지 않는다.

`fareConfidence`는 세 값이다:

| 값 | 의미 | 나오는 곳 |
|---|---|---|
| `CONFIRMED` | 그대로 믿어도 되는 실값 | 시내 대중교통, 기차·고속버스 시간표(요금이 있을 때), 육로 구간의 택시, 도보(요금이 없어 고정된 `0`도 CONFIRMED) |
| `ESTIMATE` | 가정이 들어간 추정치 | 자차 연료비(항상) |
| `UNKNOWN` | 알 수 없음(`fare: null`) | 항공(항상 — 추정하지 않는다), 요금 정보가 없는 고속버스 편, 시간표를 적용하지 못했거나 편이 없는 시외 후보 |

`CAR`의 연료비는 `거리 ÷ 연비 가정값(12km/L) × 유가`다. 유가는 오피넷 전국 평균 휘발유가를 하루 한 번(+ 서버 기동 직후) 받아 메모리에 캐시해 쓰고, `OPINET_API_KEY`가 없거나 조회에 실패하면 상수 유가로 대체한다 — 어느 쪽이든 응답 형태는 같고 `fareConfidence`도 `ESTIMATE` 그대로다(연비 가정이 이미 추정이라 유가 출처가 신뢰도를 바꾸지 않는다). `WALK`의 `fareConfidence`는 요금이 없어 고정된 `0`이므로 `CONFIRMED`다.

> **`available: false`는 조회 실패 전용이다.** 직선거리 2km 초과로 후보에서 제외된 도보와, 육로 경로가 없어 제외된 자차·택시는 `candidates` 목록에 **아예 나타나지 않는다.** `available:false`인 항목과 목록에 없는 항목을 같은 의미로 취급하면 프론트가 "도보가 왜 회색인가 — 먼 것인가 API가 죽은 것인가"를 구분할 수 없다.
>
> **`defaultMode`는 null일 수 있다** — 그 구간의 모든 후보 조회가 실패했거나(시외에서) 탈 수 있는 편이 있는 수단이 하나도 없을 때다. 프론트는 그 구간만 비워 두고 안내한다.
>
> **기본 선택과 후보 포함 여부는 서로 다른 질문이다 — 임계값 두 개가 각각 답한다. 이 판정은 시내·시외 구분 없이 구간의 직선거리로만 정해진다**(먼 시외 구간도 예외가 아니다 — 서울↔부산처럼 대중교통이 아예 불가능한 거리라도 택시 후보는 그대로 계산된다).
> - 직선거리 **300m 미만**: 대중교통·택시를 물을 거리가 아니므로 호출 자체를 생략하고 도보만 후보로 낸다.
> - 직선거리 **300m~2km**: 프로젝트의 `TransportPref`가 정한 수단(`CAR`→자차, `PUBLIC`/미지정→대중교통)이 **기본값(`defaultMode`)이자 후보 1순위**이고 택시가 항상 따라붙으며, **도보도 후보로 함께 나온다**(기본은 아니다).
> - 직선거리 **2km 초과**: 도보가 후보에서 빠진다. 기본 수단은 여전히 `TransportPref` 기준으로 정해진다.
> - 즉 **300m는 "기본 수단이 무엇인가"를, 2km는 "도보를 후보 목록에 넣을지"를 각각 결정**한다 — 300m~2km 구간에서 두 답이 겹쳐 선호 수단과 도보가 함께 후보로 나가는 것이 정상이다.

**후보 → 블록 필드 매핑** (프론트가 후보 선택 즉시 `POST /api/projects/{projectId}/blocks`를 호출할 때 쓴다. `transportMeta`는 자유 형식 객체라 아래 키는 백엔드가 강제하는 스키마가 아니라 프론트-백엔드 간 관례다):

| 후보 필드 | 블록 필드 |
|---|---|
| `durationMin`(선택된 후보 — 시외는 선택된 편의 값) | `durationMin` |
| `fare`(선택된 후보/편, 없으면 `0`) | `budget` |
| `label` | `subCategory` |
| (고정) | `category: TRANSPORT` |
| 선택된 `Candidate` 원본 + `departureName`(시외에서 고른 편 이름, 시내면 `null`) | `transportMeta.chosen` |
| 해당 `Segment`의 `intercity`/`timetableApplied`/`timetableSkipReason`/`referenceAt` | `transportMeta.segment` |
| 그 구간의 `candidates[]` 스냅샷 그대로(편집 재선택용, 재조회 없이 재렌더) | `transportMeta.candidates` |
| (고정) | `transportMeta.generated: true` |

`transportMeta.chosen`과 `transportMeta.candidates[]`의 원소는 같은 모양이다 — 둘 다 이 응답의 `Candidate`를 가공 없이 그대로 담는다. `chosen`만 그 후보 하나에 `departureName`을 얹는다. 프론트는 열람 시 `chosen`만 읽고, 편집 재선택 시 `candidates` 스냅샷으로 피커를 재렌더한다(재조회 없음).

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | `blockIds` 누락 또는 30개 초과(`@NotNull`/`@Size` 검증 실패) |
| `PROJECT404` | 404 | 존재하지 않는 프로젝트 |
| `TRANSIT400_1` | 400 | `blockIds` 중 존재하지 않거나 이 프로젝트 소속이 아닌 블록이 있음(없는 id와 남의 프로젝트 블록을 구분해 응답하지 않는다 — id를 넣어보는 것만으로 남의 블록 존재 여부를 알아내는 것을 막기 위해서다) |
| `TRANSIT400_2` | 400 | 요청에 포함된 블록 중 하나라도 좌표(`lat`/`lng`)가 없음(예: `ETC`) — 구간의 끝점 여부와 무관하게 **요청 전체**를 검사한다 |
| `GROUP403` | 403 | 그룹 멤버가 아님 |

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

길찾기 소요 시간·요금(BLK-04). **대중교통 전용 엔드포인트다** — `mode`는 `BUS`\|`SUBWAY`만 지원한다. 도보·택시·자차를 포함한 통합 조회는 `POST /api/projects/{projectId}/transit-candidates`(위 "상세 명세 — 교통 후보" 참조)가 대신 제공한다.

**Query Params:**

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `sx` / `sy` | Y | 출발 경도/위도 |
| `ex` / `ey` | Y | 도착 경도/위도 |
| `mode` | Y | `BUS`\|`SUBWAY` |

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "durationMin": 42, "fare": 1400, "intervalMin": 13, "estimated": false, "fareConfidence": "CONFIRMED" }
}
```
> `intervalMin`은 ODsay의 `totalIntervalTime`(환승 구간 배차간격 합, 분)이다.
> 실패·경로없음 시 하버사인 추정 없이 즉시 실패(대중교통은 정류장 배치·환승 구조상 직선거리 추정이 부정확해 의미 없다고 판단) — `502`(API 실패) 또는 `404`(경로 없음)를 반환하며 프론트는 곧장 수동 입력으로 전환한다.
> `fareConfidence`는 이 엔드포인트에서 항상 `CONFIRMED`다(ODsay 실측). `ESTIMATE`는 자차 연료비처럼 가정이 들어간 값에 쓰며, 교통 후보 API(`CAR`)에서만 나타난다.

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `TRANSIT400` | 400 | `mode`가 `BUS`/`SUBWAY`가 아님 |
| `TRANSIT404` | 404 | 해당 구간의 대중교통 경로를 찾을 수 없음 |
| `TRANSIT502` | 502 | ODsay API 응답 실패 |

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

두 모드 모두 **사용자 입력은 자유 형식**이다. 키워드를 따로 받는 단계는 없다(BOT-02) — 이음이가 문장을 해석해 검색 조건을 스스로 도출한다. 프로젝트 `keywords`는 값이 있으면 참고하는 선택적 컨텍스트이며, 챗봇이 이 값을 수집·저장하지 않는다.

**공통 — 여행 메타데이터 상시 주입**: 서버가 프로젝트에서 목적지·여행 기간(일수 포함)·정산 인원·선호 교통수단·키워드를 읽어 시스템 프롬프트에 넣는다(BOT-03 컨텍스트). 클라이언트가 보내지 않는다 — 도구가 서버 측 목적지로 조회하므로 클라이언트 값을 쓰면 프롬프트와 도구가 서로 다른 목적지를 가리킬 수 있다. 값이 없는 항목은 `(unset)`으로 명시해 모델이 되물을 근거로 삼게 한다.

**GENERAL 모드**: 지도 비연동. LLM이 메시지를 분석해 필요한 도구를 판단해 호출한다. 도구를 안 쓰면 `candidates`는 빈 배열.

등록 도구:

| 도구 | 용도 |
|---|---|
| 장소검색 | 프로젝트 목적지 기준 검색. **기준 장소를 함께 넘기면 그 좌표 주변을 거리순으로** 찾는다("둘째 날 두 번째 일정 근처 카페") |
| 보드 조회 | 현재 확정 일정(Day별)과 후보풀을 읽는다. 블록마다 Day 안 순번·좌표·예산을 담아, "2번째 블록" 같은 지시와 예산 질문이 성립한다 |
| 도보 · 택시 | 두 지점 간 소요시간·요금 |
| 기차 · 버스 · 항공 시간표 | 출발 후보 조회 |
| 지역 축제 | 목적지·여행 기간과 겹치는 축제. 겹침 구간은 서버가 계산해 넘긴다 |

**보드 조회는 상시 주입이 아니라 도구다.** 대다수 질문은 일정을 참조하지 않으므로 매 메시지에 보드를 실으면 안 쓰는 호출에도 토큰이 나간다. 반대로 목적지·기간·인원처럼 작고 거의 항상 관련된 값은 프롬프트에 상시 주입한다. 보드 조회는 한 메시지 안에서 지연·1회이며, 장소·경로 도구가 좌표를 해석할 때도 같은 결과를 공유한다.

**좌표는 보드를 먼저 본다.** 사용자가 경로나 "근처"의 기준으로 삼는 곳은 대개 이미 일정에 올려둔 블록이고 그 블록에는 실좌표가 있다. 그래서 도구는 장소 이름을 받아 보드에서 먼저 찾고, 없을 때만 카카오로 검색한다 — 일정 안의 장소는 카카오 재검색이 일어나지 않는다.

가게 영업여부·폐업·리뷰처럼 학습 지식만으로 답할 수 없는 실시간 정보는 Anthropic `web_search` 서버 도구로 실검색해 요약 응답한다. 남용·비용 방지를 위해 시스템 프롬프트와 `max_uses`로 용도를 실시간 확인이 필요한 정보에 한정한다(날씨·환율처럼 검색으로도 신뢰하기 어려운 값은 제외). Spring AI 1.1.8이 서버 도구 선언 API를 제공하지 않아, `AnthropicApi`의 RestClient 인터셉터로 요청에 도구를 주입하고 응답의 검색 결과 블록을 정규화한다.

**MAP 모드**: `mapContext`(현재 지도 뷰포트의 남서·북동 좌표) 필수. GENERAL과 마찬가지로 **LLM이 검색어를 도출해 도구를 호출**하며, 다른 점은 그 도구가 뷰포트 범위로 결과를 한정한다는 것이다(카카오 로컬 `rect` 파라미터). 등록 도구는 뷰포트 장소검색 **하나뿐**이다 — 축제는 일반 채팅 전용(BOT-05)이고 경로·시간표는 "보이는 범위에서 장소를 고른다"는 흐름과 무관하며, 노출 도구가 적을수록 모델의 선택 정확도가 높다. 검색어에 목적지 문자열을 붙이지 않는다(보이는 범위가 곧 검색 범위다).

> 뷰포트가 아주 넓으면(전국 규모) 범위 제한이 사실상 무의미해져 결과가 산개한다. 오류는 아니며 카카오도 정상 응답한다.

공통: 후보 좌표는 **카카오 로컬 응답 또는 축제 테이블의 실측값**만 사용한다(LLM 환각 좌표 차단). 서버는 블록을 생성하지 않고 `candidates`로만 반환하며, 실제 블록 생성은 사용자가 후보 목록에 담는 시점에 `POST /api/projects/{projectId}/blocks`로 이뤄진다. 4초 초과 대비 프론트 로딩 표시.

**Request Body:**
```json
{
  "message": "비 오는 날 실내에서 갈 만한 곳 추천해줘",
  "mode": "MAP",
  "mapContext": { "swLat": 33.44, "swLng": 126.93, "neLat": 33.47, "neLng": 126.95 }
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `message` | string | Y | 공백 불가, 2000자 이하 |
| `mode` | enum | N | `GENERAL` \| `MAP`. **미지정이면 `GENERAL`** |
| `mapContext` | object | 조건부 | `mode: MAP`일 때 필수. 카카오맵 SDK `map.getBounds()`의 `getSouthWest()`/`getNorthEast()`를 그대로 보낸다. `GENERAL`일 때는 무시된다 |

> 중심 좌표+배율이 아니라 bounds를 받는다 — SDK가 bounds를 직접 주고 카카오 로컬 `rect`도 남서·북동 좌표를 그대로 받으므로 역산이 불필요하다.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "reply": "비 오는 날에는 이런 실내 명소를 추천해요.",
    "candidates": [
      {
        "name": "제주 아쿠아플라넷",
        "category": "SPOT",
        "lat": 33.4310,
        "lng": 126.9280,
        "address": "제주 서귀포시 성산읍",
        "placeId": "12345",
        "source": "KAKAO",
        "subCategory": "관광명소",
        "eventStartDate": null,
        "eventEndDate": null,
        "detail": null
      }
    ]
  }
}
```

| 필드 | 설명 |
|---|---|
| `category` | `SPOT` / `FOOD` / `STAY`. 카카오 카테고리를 접어서 넣는다(카페·음식점→`FOOD`, 숙박→`STAY`, 그 외·미분류→`SPOT`). 축제는 항상 `SPOT` |
| `placeId` | 출처 시스템의 원본 ID — 카카오 place id 또는 축제 `contentId`. `source=KAKAO`면 `https://place.map.kakao.com/{placeId}`로 딥링크를 파생할 수 있다 |
| `source` | `KAKAO`(장소검색) / `BOT`(축제) |
| `eventStartDate`·`eventEndDate` | 축제만 채워지고 장소는 `null` |
| `detail` | 블록 생성 시 그대로 `POST /blocks`의 `detail`에 실으면 되는 문구. 축제는 `"개최 기간: 2026-08-11 ~ 2026-08-12"`가 들어가고 장소는 `null`이다 — 블록에 기간 컬럼이 없어 자유텍스트로만 남길 수 있기 때문이다 |

> `candidates`는 **항상 배열**이다(추천이 없으면 빈 배열). `blockId`는 포함하지 않는다 — 서버가 블록을 만들지 않으므로 아직 존재하지 않는 값이다. 좌표가 없는 축제는 후보에서 제외된다(장소성 블록은 좌표가 필수라 그대로 내보내면 블록 생성이 `BLOCK400`으로 실패한다).

**Errors:**

| code | HTTP | 상황 |
|---|---|---|
| `COMMON400_1` | 400 | `message` 공백/2000자 초과, `mapContext` 좌표 누락·범위 초과 |
| `CHATBOT400` | 400 | `mode: MAP`인데 `mapContext` 누락 |
| `CHATBOT502` | 502 | GMS/LLM 호출 실패 |
| `CHATBOT500` | 500 | 대화 이력 저장 실패 |

### GET /api/projects/{projectId}/chatbot/messages

프로젝트+멤버 단위로 저장된 최근 대화 이력을 추천 후보(`candidates`)까지 포함해 반환한다. 새로고침해도 챗봇 대화(추천 카드 포함)를 복원할 수 있게 프론트가 위젯 마운트 시 호출한다.

저장 이력은 최근 **10턴**(user+assistant 쌍)까지 남는다. 단 다음 `POST /messages` 호출의 LLM 프롬프트에는 비용 절감을 위해 이 중 마지막 6턴만 실린다 — 화면 복원 깊이와 LLM 컨텍스트 비용을 분리한 것이다. TTL은 1일이며, 그 이전이나 10턴을 넘는 대화는 복원되지 않는다.

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": {
    "turns": [
      { "role": "user", "content": "제주 추천해줘", "candidates": [] },
      {
        "role": "assistant",
        "content": "성산일출봉 어때요",
        "candidates": [
          {
            "name": "성산일출봉",
            "category": "SPOT",
            "lat": 33.4581,
            "lng": 126.9425,
            "address": "제주 서귀포시 성산읍",
            "placeId": "12345",
            "source": "KAKAO",
            "subCategory": "관광명소",
            "eventStartDate": null,
            "eventEndDate": null,
            "detail": null
          }
        ]
      }
    ]
  }
}
```

| 필드 | 설명 |
|---|---|
| `turns` | 시간순 배열. 대화가 없으면 빈 배열 |
| `turns[].role` | `user` \| `assistant` |
| `turns[].candidates` | 그 턴의 추천 후보 — 필드 구성은 `POST /messages` 응답의 `candidates`와 동일. 없으면 빈 배열 |

---

## 상세 명세 — 축제

### GET /api/festivals/{contentId}/homepage

축제 공식 홈페이지 URL 조회(블록 카드 외부 링크 이동용). 나이틀리 배치(`FestivalBatchService`)가 TourAPI `detailCommon2`로 미리 채워 둔 `Festival.homepage`를 DB에서 읽어 줄 뿐, **호출 시점에 TourAPI를 부르지 않는다**.

**Path Params:** `contentId`(축제 블록의 `placeId`와 동일)

**Response `200`:**
```json
{
  "isSuccess": true,
  "code": "COMMON200",
  "message": "요청에 성공했습니다.",
  "result": { "url": "http://www.boryeongmudfestival.com" }
}
```
> `url`은 홈페이지가 없거나(배치 미수집) 축제 자체가 없으면 `null`이다 — 프론트는 이때 `https://map.kakao.com/?q={축제명}` 카카오 검색으로 폴백한다. 별도 에러 코드는 없다.

---

## WebSocket / STOMP

REST로 변경 요청을 보내면, 서버가 seq를 붙여 STOMP로 전파한다. 프레즌스·커서는 seq 없이 별도 토픽(휘발성이라 재전송 대상 제외).

### 채널

| 채널 | 방향 | 용도 |
|---|---|---|
| `CONNECT` (헤더 `Authorization`) | C→S | JWT 검증, 세션에 memberId + **토큰 만료 시각** 바인딩 |
| `SUBSCRIBE` | C→S | **토큰 만료 확인 → destination 인가** — projectId 파싱 후 그룹 멤버십 검증, 실패 시 프레임 거부 |
| SUB `/topic/project/{id}` | S→C | 블록·프로젝트·멤버 변경 op (seq 포함) |
| SUB `/topic/project/{id}/presence` | S→C | 접속/이탈/편집 배지(=텍스트 락 상태) |
| SUB `/topic/project/{id}/cursor` | S→C | 라이브 커서 |
| SEND `/app/project/{id}/cursor` | C→S | `{x, y, dayNo}` 50ms 스로틀 — DB 미저장, 릴레이 |
| SEND `/app/project/{id}/voice/signal` | C→S | WebRTC 시그널링 `{type: OFFER\|ANSWER\|ICE, targetMemberId, payload}` — **대상 멤버십 검증 후 중계**(비멤버면 조용히 폐기) |
| SUB `/user/queue/voice` | S→C | 시그널 개인 수신(`convertAndSendToUser`) |

> **SUBSCRIBE 인가(필수 보안 요건)**: CONNECT의 JWT 검증은 "로그인 사용자"까지만 거른다. `ChannelInterceptor`에서 SUBSCRIBE·`/app` SEND destination의 projectId를 파싱 → 그룹 멤버십 검증 → 실패 시 거부. 통과한 projectId는 세션 어트리뷰트에 캐시(고빈도 프레임 DB 조회 회피).
> **voice 시그널 대상 검증**: destination 인가는 *보내는 쪽*만 본다. payload의 `targetMemberId`가 해당 프로젝트의 멤버인지 서버가 따로 확인하고, 아니면 전달하지 않는다(warn 로그만 남김 — 발신자에게 오류를 돌려주면 멤버 존재 여부를 캐볼 수 있다). 이 검증이 없으면 임의 memberId로 OFFER를 보내 피해자 마이크를 여는 경로가 열리며, 클라이언트를 우회한 직접 STOMP 전송이 가능하므로 프론트 수정만으로는 막을 수 없다. 시그널은 연결 수립 때만 오가는 저빈도 프레임이라 매번 조회해도 커서와 달리 비용 문제가 없다.
> **토큰 만료 (GRP-09)**: CONNECT 이후 프레임에는 토큰이 실리지 않는다. CONNECT 때 access 토큰의 `exp`를 세션 어트리뷰트에 남기고 SUBSCRIBE/SEND마다 확인해, 만료 뒤의 프레임은 거부한다(연결도 함께 끊긴다). 이것이 없으면 유효 토큰으로 한 번 연결한 세션은 만료·로그아웃과 무관하게 영원히 유효하다.
> 남는 틈: 이미 성립한 구독은 브로커가 직접 밀어주므로 인바운드 인터셉터를 타지 않는다. 즉 *완전히 유휴한* 뷰어는 만료 후에도 수신이 이어지고, 프레임을 하나라도 보내는 순간 끊긴다. 활성 사용자는 즉시 차단된다.
> **탈퇴 시 세션 강제 종료 (GRP-09)**: `WsSessionRegistry`가 memberId→세션 매핑과 **전송 세션(WebSocketSession)** 을 함께 들고 있다가, 그룹 탈퇴(`leaveGroup`)·회원 탈퇴(`withdraw`) 시 그 멤버의 모든 세션을 `CloseStatus.POLICY_VIOLATION`으로 닫는다. 인가 캐시만 비우는 방식으로는 부족하다 — 브로커의 푸시는 인터셉터를 타지 않으므로 이미 성립한 구독이 그대로 살아 있다. 탈퇴하지 않은 다른 그룹의 세션까지 끊기지만, 클라이언트가 곧바로 재연결하며 다시 인가받으므로 기능 손실은 없다.

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
