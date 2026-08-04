// features/dashboard/api/dashboardApi.js
//
// 대시보드(프로젝트 보드) REST 계층. 명세: docs/api/dashboard-api.md
//
// ── 경계 정규화 ──────────────────────────────────────
// 서버 블록 모델과 화면(pages/Dashboard)의 블록 모델은 이름이 다르다:
//
//   서버: blockId, category(대문자), subCategory, durationMin, budget, startTime("HH:mm")
//   화면: id,      cat(소문자),      sub,          dur,          cost,   startMins(분)
//
// groupApi 의 withId 와 같은 원칙으로 이 경계에서 한 번만 변환하고,
// 이 아래(훅·컴포넌트)로는 화면 모델 하나만 흐르게 한다. 나중에 화면 모델을
// 명세 이름으로 통일하더라도 이 파일의 변환만 얇아지면 된다.
//
// X-Client-Id 헤더는 axiosInstance 인터셉터가 변경 요청에 자동 첨부한다.

import axiosInstance from "../../../global/api/axiosInstance";

function unwrap(data) {
  return data?.result ?? data;
}

/** 화면의 에러 분기({ code })를 위해 백엔드 응답 본문을 그대로 던진다 (groupApi 와 동일) */
function unwrapError(error) {
  throw error.response?.data ?? error;
}

// ── 값 변환 ──────────────────────────────────────────

// 서버 category(enum 대문자) ↔ 화면 cat(소문자). 화면은 TRANSPORT 를 trans 로 줄여 쓴다.
// ⚠️ 단순 대소문자 변환(toUpperCase/toLowerCase)으로 대신할 수 없다 —
//    "trans".toUpperCase() === "TRANS" ≠ "TRANSPORT". 반드시 이 매핑을 쓴다.
//    (폼 등 화면 쪽에서도 필요해 export 한다.)
export const CAT_FROM_SERVER = {
  SPOT: "spot",
  FOOD: "food",
  STAY: "stay",
  ETC: "etc",
  TRANSPORT: "trans",
};
export const CAT_TO_SERVER = {
  spot: "SPOT",
  food: "FOOD",
  stay: "STAY",
  etc: "ETC",
  trans: "TRANSPORT",
};

/** "09:30" → 570. null(시각 없는 느슨한 블록)은 그대로 null */
export function timeToMins(time) {
  if (time == null) return null;
  const [h, m] = time.split(":").map(Number);
  return h * 60 + m;
}

/**
 * 570 → "09:30". null 은 그대로 null.
 *
 * 1439(23:59)를 넘는 값은 여기서 자르지 않는다 — 서버가 "HH:mm" 를
 * java.time.LocalTime(최대 23:59)으로 파싱하므로 "24:00" 이상은 저장할 수 없지만,
 * 조용히 잘라내면 종료−시작 == 소요시간 불변식이 깨진다. 넘치는 변경은 애초에
 * 만들어지지 않게 화면에서 막는다(index.jsx 의 chainOverflowsMidnight · DAY_END).
 */
export function minsToTime(mins) {
  if (mins == null) return null;
  const h = String(Math.floor(mins / 60)).padStart(2, "0");
  const m = String(mins % 60).padStart(2, "0");
  return `${h}:${m}`;
}

// ── 블록 모델 변환 ────────────────────────────────────

/**
 * 서버 블록 → 화면 블록. 화면이 아직 안 쓰는 필드(orderKey·lat 등)도 함께 실어 둔다.
 * 스냅샷의 blocks 와 BLOCK_CREATED op 의 payload.block 이 같은 모양이라 양쪽이 공유한다.
 */
export function toUiBlock(b) {
  return {
    id: b.blockId,
    dayNo: b.dayNo, // null = 후보(POOL)
    orderKey: b.orderKey,
    cat: CAT_FROM_SERVER[b.category] ?? "etc",
    sub: b.subCategory ?? null,
    name: b.name,
    dur: b.durationMin,
    startMins: timeToMins(b.startTime),
    endMins: timeToMins(b.endTime),
    isTimeFixed: b.isTimeFixed ?? false,
    cost: b.budget ?? 0,
    detail: b.detail ?? null,
    lat: b.lat ?? null,
    lng: b.lng ?? null,
    placeId: b.placeId ?? null,
    address: b.address ?? null,
    vehicleFlag: b.vehicleFlag ?? null,
    transportMeta: b.transportMeta ?? null,
    source: b.source,
    authorId: b.authorId,
    fieldUpdatedAt: b.fieldUpdatedAt ?? {},
    // "자동 생성" 표식 — 서버에 auto 필드가 없어 transportMeta.generated 로 실어 둔다
    // (자유 형식 jsonb). 재생성 시 삭제 대상 판별과 "자동" 배지 표시에 쓴다.
    auto: b.transportMeta?.generated === true,
  };
}

/**
 * 화면 블록 → 생성 요청 바디.
 * 명세의 생성 바디에는 detail 이 없다 — 세부 내용은 생성 후 PATCH /fields 로만 저장한다.
 */
function toCreatePayload(block) {
  return {
    category: CAT_TO_SERVER[block.cat] ?? "ETC",
    name: block.name,
    dayNo: block.dayNo ?? null, // null 이면 후보(POOL) 생성
    orderKey: block.orderKey ?? undefined, // 미지정 시 서버가 말단 키 부여
    lat: block.lat ?? undefined, // 장소성 카테고리(SPOT·FOOD·STAY)는 필수 — 누락 시 BLOCK400
    lng: block.lng ?? undefined,
    placeId: block.placeId ?? undefined,
    address: block.address ?? undefined,
    subCategory: block.sub ?? undefined,
    durationMin: block.dur ?? undefined, // 미지정 시 서버 기본 60
    startTime: minsToTime(block.startMins) ?? undefined,
    endTime: minsToTime(block.endMins) ?? undefined,
    isTimeFixed: block.isTimeFixed ?? false,
    budget: block.cost ?? 0,
    vehicleFlag: block.vehicleFlag ?? undefined, // ETC 전용 — 위반 시 BLOCK400 계열
    source: block.source ?? "MANUAL",
    transportMeta: block.transportMeta ?? undefined,
  };
}

/**
 * BLOCK_FIELD_UPDATED op 의 payload.fields(서버 필드명·서버 값) → 화면 블록 패치.
 * 모르는 필드는 조용히 무시한다 — 서버가 필드를 추가해도 구버전 화면이 깨지지 않는다.
 */
export function serverFieldsToUiPatch(fields) {
  const patch = {};
  for (const [field, value] of Object.entries(fields ?? {})) {
    switch (field) {
      case "name":
        patch.name = value;
        break;
      case "detail":
        patch.detail = value;
        break;
      case "budget":
        patch.cost = value ?? 0;
        break;
      case "durationMin":
        patch.dur = value;
        break;
      case "startTime":
        patch.startMins = timeToMins(value);
        break;
      case "endTime":
        patch.endMins = timeToMins(value);
        break;
      case "isTimeFixed":
        patch.isTimeFixed = value;
        break;
      case "vehicleFlag":
        patch.vehicleFlag = value;
        break;
      case "transportMeta":
        patch.transportMeta = value;
        break;
      default:
        break;
    }
  }
  return patch;
}

// ── 스냅샷 ───────────────────────────────────────────

/**
 * 대시보드 스냅샷 (최초 로딩·재연결 재로딩 겸용).
 * lastSeq 는 지금(1인 모드)은 쓰지 않지만 실시간 단계에서 op 동기화 기준이 되므로
 * 훅이 보관해 둔다 — 여기서 버리면 나중에 스냅샷을 또 받아야 한다.
 */
export async function fetchSnapshot(projectId) {
  try {
    const { data } = await axiosInstance.get(`/projects/${projectId}`);
    const result = unwrap(data);
    return {
      project: result.project,
      blocks: (result.blocks ?? []).map(toUiBlock),
      members: result.members ?? [],
      lastSeq: result.lastSeq ?? 0,
    };
  } catch (error) {
    unwrapError(error);
  }
}

/**
 * 유실 op 재전송 — 시퀀서의 갭 복구용. afterSeq 초과분을 seq 순서로 돌려주며,
 * 저장 전문 그대로라 실시간 수신분과 형태가 완전히 같다.
 * @returns {Promise<Array<{seq, type, actorId, clientId, payload}>>}
 */
export async function fetchOpsAfter(projectId, afterSeq) {
  try {
    const { data } = await axiosInstance.get(`/projects/${projectId}/ops`, {
      params: { afterSeq },
    });
    return unwrap(data) ?? [];
  } catch (error) {
    unwrapError(error);
  }
}

// ── 챗봇 ─────────────────────────────────────────────

/**
 * 챗봇 메시지 전송 — 서버가 GMS(Anthropic Claude)로 중계한다.
 * 프로젝트+멤버 단위로 최근 대화 히스토리가 서버에 유지된다.
 *
 * @param {"GENERAL"|"MAP"} [options.mode] 미지정이면 GENERAL.
 *        MAP 은 mapContext(지도 뷰포트 남서·북동 좌표)가 필수 — 그 범위 안에서 추천한다.
 * @returns {Promise<{reply: string, candidates: Array<{
 *   name, category, lat, lng, address, placeId, source, subCategory,
 *   eventStartDate, eventEndDate, detail
 * }>>>} candidates 는 그대로 블록 생성에 넘길 수 있는 형태(추천 없으면 빈 배열)
 */
export async function sendChatbotMessage(
  projectId,
  { message, mode = "GENERAL", mapContext },
) {
  try {
    const { data } = await axiosInstance.post(
      `/projects/${projectId}/chatbot/messages`,
      { message, mode, ...(mapContext ? { mapContext } : {}) },
      // LLM 응답은 전역 기본(10초)을 넘기기 쉽다 — 이 요청만 넉넉히
      { timeout: 30000 },
    );
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 교통 후보 ────────────────────────────────────────

/**
 * 교통 후보 일괄 계산 — 체인 순서의 블록 id 를 받아 "연속 구간마다" 이동수단
 * 후보를 돌려준다. 두 블록 사이만 원하면 그 둘만, 전체 추천이면 체인 전체를 넘긴다.
 * ⚠️ 서버는 블록을 생성하지 않는다 — 교통 블록 생성·저장은 기존대로 클라이언트 몫.
 *
 * @param {number[]} blockIds 체인 순서의 서버 블록 id (최대 30개)
 * @returns {Promise<{segments: Array<{
 *   fromBlockId, toBlockId,
 *   defaultMode: "TRANSIT"|"TAXI"|"CAR"|"WALK",
 *   candidates: Array<{mode, label, available, durationMin, fare,
 *                      fareConfidence: "CONFIRMED"|"ESTIMATE", intervalMin, distanceM}>
 * }>}>}
 */
export async function calculateTransitCandidates(projectId, blockIds) {
  try {
    const { data } = await axiosInstance.post(
      `/projects/${projectId}/transit-candidates`,
      { blockIds },
      // 외부 경로 API 를 여러 구간 조회할 수 있어 전역 기본(10초)보다 넉넉히
      { timeout: 30000 },
    );
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 세부 내용 편집 락 (advisory) ─────────────────────
// Redis SET NX + TTL 30s. 서버가 detail 쓰기를 막지는 않는다 — 편집 배지용이다.
// 락 상태 변화(획득·해제)는 presence 토픽에 DETAIL_LOCK 메시지로 전파된다.

/**
 * 편집 락 획득. 실패해도 편집을 막지 않는다(advisory) — holder 를 배지에 쓴다.
 * @returns {Promise<{acquired: boolean, holder: number|null, ttlRemaining: number}>}
 */
export async function acquireDetailLock(blockId) {
  try {
    const { data } = await axiosInstance.post(`/blocks/${blockId}/detail-lock`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/** 락 TTL 연장 — 10초 주기, 소유자만 가능(비소유 하트비트 = BLOCK409). */
export async function heartbeatDetailLock(blockId) {
  try {
    const { data } = await axiosInstance.put(`/blocks/${blockId}/detail-lock`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/** 락 해제 — 멱등(만료 직후 해제 요청도 에러가 아니다). */
export async function releaseDetailLock(blockId) {
  try {
    const { data } = await axiosInstance.delete(`/blocks/${blockId}/detail-lock`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 블록 CRUD ────────────────────────────────────────

/** @returns {Promise<{blockId: number, seq: number}>} */
export async function createBlock(projectId, block) {
  try {
    const { data } = await axiosInstance.post(
      `/projects/${projectId}/blocks`,
      toCreatePayload(block),
    );
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/**
 * 필드 단위 LWW 배치 갱신.
 * @param fields 서버 필드명 기준의 평면 객체 — 예: { budget: 15000, detail: "..." }
 *        (화면 필드명 → 서버 필드명 매핑은 호출부 책임. 어느 화면 필드가 어느 서버
 *         필드인지는 저장 폼마다 달라서 여기서 일괄 변환하면 오히려 숨는다.)
 * @returns {Promise<{applied: Record<string, boolean>}>}
 *          applied[field] === false 는 더 최신 값이 있어 무시됐다는 뜻(스테일).
 *          1인 모드에서는 나올 수 없으므로, 나온다면 그 자체가 조사 대상이다.
 */
export async function updateBlockFields(blockId, fields) {
  try {
    const body = {
      fields: Object.entries(fields).map(([field, value]) => ({ field, value })),
    };
    const { data } = await axiosInstance.patch(`/blocks/${blockId}/fields`, body);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/**
 * 블록 이동 — 체인 재정렬 / 후보↔체인 / Day 이동.
 * 후보로 보낼 때는 dayNo: null. 이동 후 영향받은 블록들의 시각 재계산·저장은
 * 명세(320행)상 클라이언트 몫이며 updateBlockFields 로 이어서 처리한다.
 */
export async function moveBlock(blockId, { dayNo, orderKey }) {
  try {
    const { data } = await axiosInstance.patch(`/blocks/${blockId}/position`, {
      dayNo,
      orderKey,
    });
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/** 소프트 삭제(tombstone). 이후 이 블록에 도착하는 op 는 서버가 BLOCK410 으로 거부한다. */
export async function deleteBlock(blockId) {
  try {
    const { data } = await axiosInstance.delete(`/blocks/${blockId}`);
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

// ── 프로젝트 속성 ────────────────────────────────────

/**
 * 목표 예산(전체 총액) 변경 — 전용 엔드포인트(PATCH /projects/{id}/budget).
 * null 을 보내면 예산 미설정으로 초기화된다.
 * @returns {Promise<{targetBudget: number}>}
 */
export async function updateTargetBudget(projectId, targetBudget) {
  try {
    const { data } = await axiosInstance.patch(
      `/projects/${projectId}/budget`,
      { targetBudget },
    );
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/** PLANNING ↔ DONE 양방향 전환 */
export async function updateProjectStatus(projectId, status) {
  try {
    const { data } = await axiosInstance.patch(`/projects/${projectId}/status`, {
      status,
    });
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}

/** 정산 인원(1인당 표시용). null 이면 그룹 멤버 수 연동으로 복귀 */
export async function updateBudgetHeadcount(projectId, headcount) {
  try {
    const { data } = await axiosInstance.patch(
      `/projects/${projectId}/budget-headcount`,
      { headcount },
    );
    return unwrap(data);
  } catch (error) {
    unwrapError(error);
  }
}
