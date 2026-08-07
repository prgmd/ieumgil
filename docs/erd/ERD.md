# ERD 설계 문서

> 서비스명: 이음길 (그룹 여행 계획 & 실시간 협업 플랫폼)
> 기준: 백엔드 설계 v3 / 기능명세서 2026-07-16 / 통합 프로토타입
> 작성일: 2026-07-28 — 백엔드 설계 v3 스키마 기준으로 정리
> DB: PostgreSQL(RDS) — JSONB · partial index 활용

> **타임스탬프 타입은 `TIMESTAMP`(timezone 없음)** — 엔티티의 시각 필드가 전부 `LocalDateTime`이라 Hibernate가 `timestamp without time zone`으로 컬럼을 생성한다. tz 보존이 필요해지면 엔티티를 `OffsetDateTime`/`Instant`로 바꿔야 한다.
>
> **`created_at`/`updated_at`은 JPA Auditing(`@CreatedDate`/`@LastModifiedDate`)이 채운다** — DB 레벨 `NOT NULL` 제약이나 `DEFAULT NOW` 절은 생성되지 않는다(`BaseTimeEntity` 참조). 아래 표의 `NOT NULL, DEFAULT NOW` 표기는 "앱이 항상 채운다"는 논리 제약이며, 직접 SQL로 INSERT하면 null이 들어갈 수 있다.

---

## 목차

1. [다이어그램](#다이어그램)
2. [엔티티 목록](#엔티티-목록)
3. [엔티티 상세 정의](#엔티티-상세-정의)
4. [관계 요약](#관계-요약)
5. [DB에 저장하지 않는 것](#db에-저장하지-않는-것)

---

## 다이어그램

```mermaid
erDiagram
    MEMBER ||--o{ GROUP_MEMBER : "소속"
    TRAVEL_GROUP ||--o{ GROUP_MEMBER : "구성"
    TRAVEL_GROUP ||--o{ PROJECT : "보유"
    PROJECT ||--o{ BLOCK : "포함"
    PROJECT ||--o{ ACTIVITY_LOG : "기록"
    MEMBER ||--o{ BLOCK : "작성"
    MEMBER ||--o{ BLOCK : "마지막 편집"
    MEMBER ||--o{ ACTIVITY_LOG : "발생"

    MEMBER {
        bigint id PK
        bigint kakao_id "카카오 고유 ID, UK, 탈퇴 시 null"
        varchar nickname "탈퇴 시 '탈퇴한 멤버'로 값 교체"
        varchar profile_image_url "nullable, 탈퇴 시 null"
        timestamp created_at
        timestamp updated_at
        timestamp deleted_at "탈퇴 시각, nullable"
    }
    TRAVEL_GROUP {
        bigint id PK
        varchar name "2~20자"
        varchar invite_code "UK, I·O·0·1 제외"
        timestamp invite_expires_at "발급 +7일"
        timestamp created_at
        timestamp updated_at
        timestamp deleted_at "소프트 삭제, +30일 후 완전 삭제"
    }
    GROUP_MEMBER {
        bigint group_id PK_FK
        bigint member_id PK_FK
        timestamp joined_at "가입 시점, 멤버 목록 표시 순서용"
    }
    PROJECT {
        bigint id PK
        bigint group_id FK
        varchar name
        varchar destination
        date start_date
        date end_date
        jsonb transport_prefs "[\"CAR\",\"PUBLIC\"]"
        int budget_headcount "1인당 표시용 정산 인원, null=현재 멤버 수 따름"
        int target_budget "프로젝트 전체 목표 예산, nullable"
        jsonb keywords "챗봇 키워드 최대 5개, nullable"
        varchar status "PLANNING | DONE (양방향)"
        timestamp done_at "되돌리면 null"
        timestamp created_at
        timestamp updated_at
        timestamp deleted_at
    }
    BLOCK {
        bigint id PK
        bigint project_id FK
        int start_offset_minutes "Day 1 00:00 기준 경과 분, null = 후보 블록"
        varchar order_key "fractional index 문자열"
        varchar category "SPOT|FOOD|STAY|ETC|TRANSPORT"
        varchar sub_category "자유 텍스트"
        varchar name
        int duration_min "소요시간(분), 종료 오프셋 파생·표시용"
        boolean is_time_fixed "시각 고정(드래그 재계산 제외) 여부"
        int budget "총액(프로젝트 전체) 원, 기본 0"
        varchar detail "최대 500자"
        decimal lat "장소성 블록 NOT NULL"
        decimal lng "장소성 블록 NOT NULL"
        varchar place_id "참조용, UNIQUE 아님"
        varchar address
        varchar vehicle_flag "START|END|null ─ 차량 구간 표식, ETC 전용"
        jsonb transport_meta "교통 블록 전용"
        varchar source "KAKAO | MANUAL | BOT"
        bigint author_id FK
        bigint last_edited_by FK "마지막 편집자(PRS-04), nullable"
        jsonb field_updated_at "필드별 서버 수신 시각(LWW)"
        timestamp created_at
        timestamp updated_at
        timestamp deleted_at "tombstone"
    }
    ACTIVITY_LOG {
        bigint id PK
        bigint project_id FK
        bigint member_id FK
        varchar op_type
        jsonb payload "브로드캐스트 op 전문(clientId 포함)"
        bigint seq "프로젝트 내 단조 증가"
        timestamp created_at
        timestamp updated_at
    }
    FESTIVAL {
        bigint id PK
        varchar content_id "TourAPI contentId, UNIQUE"
        varchar title
        varchar category "EV01|EV02|EV03 (축제|공연|행사)"
        varchar l_dong_regn_cd "법정동 광역코드 — 지역 필터링 기준"
        varchar l_dong_signgu_cd "법정동 시군구코드"
        varchar addr
        double lat
        double lng
        date event_start_date
        date event_end_date
        varchar first_image "nullable"
        varchar homepage "nullable, 공식 홈페이지"
        timestamp created_at
        timestamp updated_at
    }
```

---

## 엔티티 목록

| 엔티티 | 설명 |
|---|---|
| `MEMBER` | 서비스 회원 (소셜 로그인 기반) |
| `TRAVEL_GROUP` | 여행 그룹 (`group`은 SQL 예약어라 테이블명 변경) |
| `GROUP_MEMBER` | 그룹 멤버 (그룹-회원 소속) |
| `PROJECT` | 그룹 내 여행 프로젝트 (대시보드 보드 단위) |
| `BLOCK` | 일정 블록 / 후보 블록 (보드의 최소 단위) |
| `ACTIVITY_LOG` | 실시간 동기화 op 저널 (재전송 원본) |
| `FESTIVAL` | 지역 축제/공연/행사 (TourAPI 배치 수집, 카카오맵엔 없는 시간 한정 이벤트) |

---

## 엔티티 상세 정의

### MEMBER: 서비스 회원
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, IDENTITY | DB 내 회원 식별자 |
| kakao_id | BIGINT | UNIQUE, NULL | 카카오 고유 ID. 탈퇴 시 null |
| nickname | VARCHAR(30) | NOT NULL | 표시 이름. 탈퇴 시 `"탈퇴한 멤버"`로 값 교체 |
| profile_image_url | VARCHAR(512) | NULL | 프로필 이미지 URL. 탈퇴 시 null |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW | 가입 시점 |
| updated_at | TIMESTAMP | NOT NULL | 마지막 수정 시점(`BaseTimeEntity` 상속) |
| deleted_at | TIMESTAMP | NULL | 소프트 삭제(탈퇴) 시각 |

> **소셜 제공자는 카카오 단일** — `provider` / `provider_id` 2컬럼 대신 `kakao_id` 단일 컬럼을 쓴다. 네이버 로그인은 v1 범위에서 제외하며, 추가 시 스키마 변경이 필요하다.
>
> **UNIQUE KEY: `kakao_id`** — PostgreSQL은 UNIQUE 컬럼에 NULL을 여러 개 허용하므로, 탈퇴자가 늘어도 유니크 충돌이 없다.
>
> **탈퇴 정책(AUTH-05) — "재가입 = 신규 계정".** 계정 통합·부활은 v1 범위 외.
> - `kakao_id` → **null** (원본 소셜 ID 즉시 파기)
> - `nickname` → `"탈퇴한 멤버"`로 값 교체 (NOT NULL 충돌 없음, 블록 작성자 표기 자동 해결)
> - `profile_image_url` → null, `deleted_at` 기록. 행은 유지(BLOCK.author_id FK 보존)
> - 같은 카카오 계정으로 재가입하면 기존 행은 `kakao_id`가 null이라 조회되지 않아 새 행이 생성된다 — 충돌·부활 없음
>
> **구현 매핑**: 코드상 엔티티는 `User`(테이블 `users`), 필드는 `kakaoId` / `profileImageUrl`. API 경로·응답 필드는 `members` / `memberId` 용어를 쓴다.

---

### TRAVEL_GROUP: 여행 그룹
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, IDENTITY | 그룹 식별자 |
| name | VARCHAR(20) | NOT NULL | 그룹 이름 (2~20자) |
| invite_code | VARCHAR(8) | UNIQUE, NOT NULL | 초대 코드. 대문자+숫자(I·O·0·1 제외) 랜덤 |
| invite_expires_at | TIMESTAMP | NOT NULL | 초대 코드 만료 (발급 +7일) |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW | 생성 시점 |
| updated_at | TIMESTAMP | NOT NULL | 마지막 수정 시점(`BaseTimeEntity` 상속) |
| deleted_at | TIMESTAMP | NULL | 소프트 삭제 시각 (+30일 후 하드 삭제) |

> **방장 / owner 개념 없음 (flat 모델)** — 초대 코드로 공유하는 방 컨셉이라 모든 멤버가 동등하다. 소유자 컬럼을 두지 않는다.
> 초대 코드 재발급 시 값 교체 = 기존 코드 즉시 무효(GRP-06). 스케줄러가 30일 경과분 하드 삭제(MY-04).

---

### GROUP_MEMBER: 그룹 멤버
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| group_id | BIGINT | PK, FK(TRAVEL_GROUP) | 그룹 식별자 (ON DELETE CASCADE) |
| member_id | BIGINT | PK, FK(MEMBER) | 회원 식별자 (ON DELETE CASCADE) |
| joined_at | TIMESTAMP | NOT NULL, DEFAULT NOW† | 가입 시점 (멤버 목록 표시 순서용) |

> † 앱 기본값(`@Builder.Default now()`) — DB `DEFAULT` 절이 아니다.

> **PK: `(group_id, member_id)`** 복합키. 정원(최대 10명) 검증은 서비스 레이어에서 count 후 삽입, 동시 가입 경합은 그룹 행 `SELECT ... FOR UPDATE`로 방지. 인덱스 `ix_group_member_member (member_id)` — 회원 기준 소속 그룹 역조회용.
>
> **flat 모델 (방장 없음)** — `role` 컬럼이 없다. 모든 멤버가 동등해 그룹명 수정·초대 코드 재발급·그룹/프로젝트 삭제를 누구나 할 수 있고, 방장 승계 로직도 없다. 멤버 강제 방출(kick)은 두지 않고 **본인 탈퇴(self-leave)만** 지원 — 탈퇴 = 행 삭제(블록은 author_id로 유지). **마지막 1인이 나가면 그룹은 하드 삭제**된다(복구할 멤버가 없어 즉시 완전 삭제, 프로젝트·블록 CASCADE). 반면 명시적 그룹 삭제(confirmName)는 소프트 삭제(+30일 후 하드)로 별개.

---

### PROJECT: 여행 프로젝트
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, IDENTITY | 프로젝트 식별자 |
| group_id | BIGINT | FK(TRAVEL_GROUP) NOT NULL | 소속 그룹 (ON DELETE CASCADE) |
| name | VARCHAR(100) | NOT NULL | 프로젝트 이름 |
| destination | VARCHAR(100) | NULL | 여행지 |
| start_date | DATE | NULL | 시작일 |
| end_date | DATE | NULL | 종료일 |
| transport_prefs | JSONB | NULL | 선호 이동수단(복수 선택 가능) `["CAR","PUBLIC"]` |
| budget_headcount | INT | NULL | 정산 인원(1인당 표시용). null=조회 시점 멤버 수 따름 |
| target_budget | INT | NULL | 프로젝트 전체 목표 예산 |
| keywords | JSONB | NULL | 챗봇 키워드 (최대 5개) |
| status | VARCHAR(10) | NOT NULL | `PLANNING` / `DONE` (양방향 전환). 앱 기본값 `PLANNING` |
| done_at | TIMESTAMP | NULL | 완료 시각 (되돌리면 null) |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW | 생성 시점 |
| updated_at | TIMESTAMP | NOT NULL | 마지막 수정 시점(`BaseTimeEntity` 상속) |
| deleted_at | TIMESTAMP | NULL | 소프트 삭제 시각 |

> **예산은 프로젝트 전체(총액) 기준.** `target_budget`은 프로젝트 전체 목표 예산이고, 지출 합계도 각 블록 `budget`(총액)의 합으로 계산한다.
> **`budget_headcount`(1인당 표시용)**: 생성 폼의 "여행 인원" 입력을 초기값으로 사용. **정산 자체는 총액 기준**이며, 이 값은 "1인당 = 총액 ÷ 인원" N빵 표시에만 쓴다. null이면 조회 시점 그룹 멤버 수로 계산, 직접 지정 후에는 멤버 수 변동에 자동 연동하지 않음(BGT-03).
> **`status`**: 양방향 전환. DONE이어도 쓰기 거부 로직을 두지 않음(NFR-05). 인덱스 `ix_project_group (group_id, deleted_at)` — 그룹의 살아있는 프로젝트 목록 조회용(`status` 인덱스는 없다).
>
> **Day 시작 시각(`day_settings`) 컬럼 제거** — 블록이 각자 절대 오프셋을 저장하므로 별도 Day 기점이 필요 없다. Day의 경계는 항상 00:00이고(Day N = 오프셋 `(N-1)*1440` ~ `N*1440 - 1`), `start_date`는 Day 1을 달력 날짜에 대응시키는 역할만 한다.

---

### BLOCK: 일정 / 후보 블록
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, IDENTITY | 블록 식별자 |
| project_id | BIGINT | FK(PROJECT) NOT NULL | 소속 프로젝트 (ON DELETE CASCADE) |
| start_offset_minutes | INT | NULL, CHECK(NULL 이거나 ≥ 0) | Day 1 00:00 기준 경과 분. **NULL = 후보 블록(POOL)**. Day 번호·하루 안의 시각·종료 시각이 전부 여기서 파생된다 |
| order_key | VARCHAR(255) | NOT NULL | fractional index 문자열 (체인 정렬) |
| category | VARCHAR(20) | NOT NULL | `SPOT` / `FOOD` / `STAY` / `ETC` / `TRANSPORT` |
| sub_category | VARCHAR(50) | NULL | 자유 텍스트 소분류 |
| name | VARCHAR(255) | NOT NULL | 블록 이름 |
| duration_min | INT | NOT NULL, DEFAULT 60† | 소요 시간(분, 양수 — 단위 제약 없음). 종료 오프셋(`start_offset_minutes + duration_min`)의 근거이자 표시값 |
| is_time_fixed | BOOLEAN | NOT NULL, DEFAULT FALSE† | 시각 고정 여부. TRUE면 드래그 재계산에서 제외(앵커) |
| budget | INT | NOT NULL, DEFAULT 0† | 예산(원) — **프로젝트 전체(총액) 기준** |
| detail | VARCHAR(500) | NULL | 세부 내용 (최대 500자) |
| lat | DECIMAL(10,7) | NULL | 위도. **장소성 블록은 NOT NULL** |
| lng | DECIMAL(10,7) | NULL | 경도. **장소성 블록은 NOT NULL** |
| place_id | VARCHAR(64) | NULL | 외부 장소 참조 ID(참조용, UNIQUE 아님) — `source=KAKAO`면 카카오 장소ID, `source=BOT`이고 TourAPI 출처면 `FESTIVAL.content_id` |
| address | VARCHAR(255) | NULL | 주소 |
| vehicle_flag | VARCHAR(10) | NULL | `START` / `END` / null — 차량 구간 표식, ETC 전용 |
| transport_meta | JSONB | NULL | 교통 블록 전용 메타 |
| source | VARCHAR(10) | NOT NULL | 생성 출처 `KAKAO` / `MANUAL` / `BOT` |
| author_id | BIGINT | FK(MEMBER) NOT NULL | 작성자 (ON DELETE 없음 = NO ACTION — 탈퇴는 소프트라 FK가 깨지지 않음) |
| last_edited_by | BIGINT | FK(MEMBER) NULL | 마지막 편집자(PRS-04). 생성 시 작성자로 시작, 필드 수정·이동이 실제 적용될 때마다 갱신. 005 마이그레이션 이전 행은 null — 표시가 작성자로 폴백 |
| field_updated_at | JSONB | NOT NULL, DEFAULT '{}'† | 필드별 서버 수신 시각 (LWW) |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW | 생성 시점 |
| updated_at | TIMESTAMP | NOT NULL | 마지막 수정 시점(`BaseTimeEntity` 상속) |
| deleted_at | TIMESTAMP | NULL | tombstone (소프트 삭제) |

> † `DEFAULT 60`/`DEFAULT FALSE`/`DEFAULT 0`/`DEFAULT '{}'`은 애플리케이션 기본값(`@Builder.Default`)이며 DB `DEFAULT` 절이 아니다 — 직접 INSERT 시 값 지정이 필수다.

> **체인 조회는 partial index** (tombstone 필터가 항상 붙으므로):
> ```sql
> CREATE INDEX ix_block_chain ON block (project_id, start_offset_minutes, order_key, id) WHERE deleted_at IS NULL;
> ```
> **정렬은 항상 `ORDER BY start_offset_minutes NULLS LAST, order_key, id`** — 보드 순서는 오프셋이 정하고, 오프셋이 같을 때만 order_key가 가른다(동시 삽입으로 order_key까지 같을 수 있어 id로 최종 tie-break). 후보(POOL)는 NULLS LAST로 맨 뒤에 모인다.
> **`budget`은 프로젝트 전체(총액) 기준 정수** — 1인당 금액은 저장하지 않고, 필요 시 `총액 ÷ budget_headcount`로 표시만 한다.
> **블록 시간 모델(`start_offset_minutes`/`duration_min`/`is_time_fixed`)**:
> - 위치는 정수 하나다. `day_no = offset / 1440 + 1`, 하루 안의 시각 = `offset % 1440`, 종료 = `offset + duration_min`. 종료가 자정을 넘어도 되감기지 않으므로 **심야 이동 블록도 행 하나**다.
> - 보드에 있다 ⟺ 오프셋이 있다. 위치와 시각이 서로 어긋날 수 없는 것이 이 모델의 요점이다(옛 `day_no`+`start_time` 조합은 어긋날 수 있었다).
> - **드래그(재정렬) 시**: `is_time_fixed=false`인 일반 블록은 앞 블록 종료 기준으로 오프셋을 다시 계산해 **위치 변경 API로 저장**한다(공백 보존). `is_time_fixed=true`(예약·교통 등 앵커)는 재계산에서 제외하고 자기 오프셋을 고수한다.
> - 블록 사이 간격(공백)은 뒤 블록의 오프셋이 앞 블록의 종료 오프셋보다 크면 자연스럽게 표현된다 — 공백은 결함이 아니라 정상 상태다.
> **카카오맵 딥링크**: 별도 컬럼 없이 `place_id`+`source`로 프론트에서 파생한다 — `source=KAKAO`일 때만 `https://place.map.kakao.com/{place_id}`로 "카카오맵에서 보기" 버튼 노출(카카오 로컬 API 응답의 `place_url` 필드와 동일 패턴). `source`가 `KAKAO`가 아니면(TourAPI 등 외부 장소ID) 버튼 자체를 숨긴다 — URL을 저장하지 않고 항상 파생시켜서 링크 깨짐/불일치를 원천 차단.
> **`vehicle_flag`**: ETC 카테고리에서만 노출. 역할은 "수단 선택의 기본값 제안"까지만 — 판정 결과를 계산에 직접 쓰지 않아 이동·삭제되어도 기존 교통 블록 오염 없음.
> **`transport_meta` 예시**:
> ```json
> {
>   "mode": "TRAIN",
>   "trainType": "KTX",
>   "depName": "서울역", "arrName": "부산역",
>   "depLat": 37.55, "depLng": 126.97,
>   "arrLat": 35.11, "arrLng": 129.04,
>   "routeMode": "SUBWAY",
>   "intervalMin": 13,
>   "estimated": true,
>   "fare": 59900,
>   "fareConfidence": "CONFIRMED"
> }
> ```
> `estimated`(시간 추정)와 `fareConfidence`(요금 신뢰도 `CONFIRMED`/`ESTIMATE`)는 별개 축. 교통 블록의 출발 시각은 `transport_meta`가 아니라 블록의 `start_offset_minutes`에 저장하고(도착은 `+ duration_min` 파생) `is_time_fixed=true`(앵커)로 둔다.
> `intervalMin`(배차간격, 분)은 `routeMode`가 `BUS`/`SUBWAY`일 때만 있는 값(ODsay `totalIntervalTime`) — 환승 구간 배차간격의 합이라 단일 노선 배차간격이 아니며, WALK/TAXI/CAR에는 없다(`null`).
> **`field_updated_at` 예시**: `{"budget":"2026-08-01T10:22:31.512Z"}` — LWW 대상은 `name`·`budget`·`duration_min`·`detail`·`is_time_fixed`·`vehicle_flag`·`transport_meta` 7종이다. **`start_offset_minutes`는 LWW 대상이 아니다** — 위치는 이동 API 하나로만 바뀌므로 필드 경로와 이동 경로가 서로 다른 위치를 주장할 여지가 없다.

---

### ACTIVITY_LOG: 실시간 동기화 op 저널
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, IDENTITY | 로그 식별자 |
| project_id | BIGINT | FK(PROJECT) NOT NULL | 소속 프로젝트 (ON DELETE CASCADE) |
| member_id | BIGINT | FK(MEMBER) NOT NULL | op 발생 주체 |
| op_type | VARCHAR(40) | NOT NULL | op 종류 (예: `BLOCK_CREATED`) |
| payload | JSONB | NOT NULL | 브로드캐스트 op 전문 (clientId 포함) |
| seq | BIGINT | NOT NULL | 프로젝트 내 단조 증가 시퀀스 |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW | 기록 시점 |
| updated_at | TIMESTAMP | NOT NULL | 마지막 수정 시점(`BaseTimeEntity` 상속, `@Immutable`이나 INSERT 시 기록) |

> **UNIQUE KEY: `(project_id, seq)`**.
> **payload에는 브로드캐스트한 op 전문을 그대로 저장** — "재전송 = 저장된 걸 그대로 쏜다"가 구조적으로 보장. 재연결 시 `WHERE project_id=? AND seq > :lastSeq ORDER BY seq`로 유실분 재전송(NFR-01).
> **seq 채번**: Redis `INCR project:{id}:seq` + 채번~브로드캐스트 구간을 프로젝트 단위 락으로 직렬화. 카운터 유실 대비 리시드는 기동 시가 아니라 **지연 보정** — `INCR` 결과가 1이면(키가 새로 만들어졌으면) 그때 `max(seq)`로 끌어올린다(`SeqGenerator`).

---

### FESTIVAL: 지역 축제/공연/행사
| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| id | BIGINT | PK, IDENTITY | 식별자 |
| content_id | VARCHAR(255) | UNIQUE, NOT NULL | TourAPI `contentid` — 배치 upsert 기준키 |
| title | VARCHAR(255) | NOT NULL | 축제/행사명 |
| category | VARCHAR(255) | NOT NULL | `EV01`(축제) / `EV02`(공연) / `EV03`(행사) |
| l_dong_regn_cd | VARCHAR(255) | NULL | 법정동 광역코드 — **지역 필터링은 이 컬럼 기준**(아래 참고) |
| l_dong_signgu_cd | VARCHAR(255) | NULL | 법정동 시군구코드 |
| addr | VARCHAR(255) | NULL | 주소(`addr1`+`addr2`) |
| lat | DOUBLE PRECISION | NULL | 위도 |
| lng | DOUBLE PRECISION | NULL | 경도 |
| event_start_date | DATE | NOT NULL | 행사 시작일 |
| event_end_date | DATE | NOT NULL | 행사 종료일 |
| first_image | VARCHAR(255) | NULL | 대표 이미지 URL |
| homepage | VARCHAR(500) | NULL | 공식 홈페이지 (배치 `detailCommon2`) |
| created_at | TIMESTAMP | NOT NULL, DEFAULT NOW | 최초 수집 시점 |
| updated_at | TIMESTAMP | NOT NULL | 마지막 배치 갱신 시점 |

> **별도 도메인(`domain.festival`), 다른 엔티티와 FK 관계 없음.** 한국관광공사 TourAPI(`contentTypeId=15`)에서 매일 새벽 배치로 수집해 upsert. 카카오맵은 상시 장소만 제공하고 "이 장소가 이 기간에만 연다"는 시간 제한 이벤트 개념이 없어서 보강하는 용도 — 그래서 `event_start_date`/`event_end_date`가 정본(카카오 데이터엔 대응 컬럼 자체가 없음).
>
> **지역 필터는 `l_dong_regn_cd` 기준, `area_code` 아님** — TourAPI 실제 응답에서 레거시 `areacode` 필드가 대부분 빈 값으로 와서 필터링에 못 씀(라이브 테스트로 확인). 법정동 코드(`lDongRegnCd`/`lDongSignguCd`)가 신뢰 가능한 값으로 채워져 있어 이걸 조회 조건으로 쓴다.
> **레포츠(`contentTypeId=28`)는 수집 대상 아님** — 상시 영업 시설이라 카카오맵과 장소가 겹칠 가능성이 높아 제외.
> **PROJECT/BLOCK과 FK 연동 없음** — 챗봇(BOT-03)이 `l_dong_regn_cd`+여행 기간으로 조회한 결과를 후보 BLOCK으로 만들 때 `BLOCK.place_id`에 `FESTIVAL.content_id`를 loosely 재사용한다(BLOCK 절의 `place_id` 설명 참조 — `source=BOT`이고 TourAPI 출처인 경우).

---

## 관계 요약

```
MEMBER ──< GROUP_MEMBER >── TRAVEL_GROUP
TRAVEL_GROUP ──< PROJECT ──< BLOCK
PROJECT ──< ACTIVITY_LOG
MEMBER ──< BLOCK (author_id, last_edited_by)
MEMBER ──< ACTIVITY_LOG (member_id)
```

- `MEMBER` ↔ `TRAVEL_GROUP`은 `GROUP_MEMBER`로 다대다. 멤버 간 권한 차이 없음(flat).
- `TRAVEL_GROUP` 1 : N `PROJECT`, `PROJECT` 1 : N `BLOCK`.
- `BLOCK.author_id`, `BLOCK.last_edited_by`, `ACTIVITY_LOG.member_id`는 `MEMBER` 참조 (탈퇴는 소프트 삭제라 FK 유지).

---

## DB에 저장하지 않는 것

명세서 설계 원칙에 따라 아래는 RDB가 아닌 Redis/메모리에 둔다.

| 항목 | 저장소 | 비고 |
|---|---|---|
| Refresh 토큰 / 로그아웃 블랙리스트 | Redis | `refresh:{memberId}`, TTL 기반 |
| 접속 · 커서 · 편집 중 배지 (presence) | Redis · 메모리 | TTL 기반(PRS) |
| 세부 내용 텍스트 편집 락 | Redis `SET NX` | TTL 30초(OI-04) |
| seq 채번 카운터 | Redis `INCR` | 카운터 유실 시 `INCR` 결과 1을 감지해 `max(seq)`로 지연 리시드 |
| 예산 합계 · Day 종료 시각 | 클라이언트 파생 계산 | 각 블록 `budget`(총액) 합산, Day 종료 = 마지막 블록 `start_offset_minutes + duration_min` |
| 교통(ODsay)·장소(카카오 로컬) 조회 결과 | 외부 API (실시간 조회) | `domain.transit`/`domain.place`, DB에 영속하지 않음 |
| 챗봇 대화 히스토리 | Redis `ChatTurn` | TTL 기반, 최근 N턴만 저장 |
