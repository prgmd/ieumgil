import React, {
  useState,
  useRef,
  useEffect,
  useCallback,
  useMemo,
} from "react";
import { useParams, useNavigate } from "react-router-dom";
import { BlockEditForm } from "./components/BlockEditForm";
import { ChatbotWidget } from "./components/ChatbotWidget";
import { RemoteCursorLayer } from "./components/RemoteCursorLayer";
import { TransitPickerModals } from "./components/TransitPickerModals";
import { VoiceBar } from "./components/VoiceBar";
import { hueOf } from "./components/memberColor";
import { buildTransportMeta } from "./transitMeta";
import { CardBody } from "./components/CardBody";
import {
  BlockEditBadge,
  BlockLinkBadge,
  BlockEditorBadge,
} from "./components/BlockBadges";
import { PoolCard } from "./components/PoolCard";
import { BudgetPanel } from "./components/BudgetPanel";
import { MapPanel } from "./components/MapPanel";
import { SearchPanel } from "./components/SearchPanel";
import { ReadModeView } from "./components/ReadModeView";
import {
  fmtTime,
  catOf,
  isTempId,
  isServerBlock,
  catFromKakaoGroup,
  dayNoOf,
  dayKeysOf,
  dayDate,
  spilloversInto,
  spilloverFloorOf,
  boardOf,
  blocksOfDay,
} from "./dashboardHelpers";
import { useKakaoMap } from "./hooks/useKakaoMap";
import { useBudget } from "./hooks/useBudget";
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  useDroppable,
  useDraggable,
} from "@dnd-kit/core";
import {
  SortableContext,
  sortableKeyboardCoordinates,
  rectSortingStrategy,
} from "@dnd-kit/sortable";
import { generateKeyBetween } from "fractional-indexing";
import { AppBar } from "../My/shared/ui/AppBar";
import EditProjectModal from "../Group/components/EditProjectModal";
import { useDashboard } from "../../features/dashboard/hooks/useDashboard";
import { useProjectOps } from "../../features/dashboard/realtime/useProjectOps";
import { useVoiceChat } from "../../features/dashboard/voice/useVoiceChat";
import { createOpSequencer } from "../../features/dashboard/realtime/opSequencer";
import * as blockApi from "../../features/dashboard/api/dashboardApi";
import { getClientId } from "../../global/api/clientId";
import { useGroupDetail } from "../../features/group/hooks/useGroupDetail";
import { useProjects } from "../../features/group/hooks/useProjects";
import { useIsMobile } from "../../global/hooks/useIsMobile";
import { useAuthStore } from "../../global/stores/authStore";
import { useToastStore } from "../../global/stores/toastStore";
import "./index.css";

const PX = 2.0;
// 드래그 스냅 1분 (QA ⓑ) — 10분 스냅이던 시절엔 분 단위 교통블록(실제 API 소요시간)
// 과 완벽하게 맞물리지 않았다. 리사이즈는 원래 분 단위라 이제 둘이 같은 정밀도다.
const SNAP = 1;
const TL_PAD_TOP = 20;
const TL_PAD_LEFT = 70;

// 고른 편 기준 door-to-door 소요 — 접근 + 대기 + 시외(+ 환승 + 연결편) + 이탈.
// 후보의 durationMin 도 door-to-door 지만 대표 편(첫 편) 기준이라 다른 편을 고르면
// 맞지 않고, 편의 durationMin 은 시외 leg 하나만의 소요다(환승이면 첫 leg 만).
// 교통 블록은 앞 블록이 끝나는 순간부터 시작하므로(startMins = 앞 블록 끝) 접근도 포함한다.
// 조각이 하나라도 없으면 null 이다 — 고속버스 시간표는 소요를 주지 않을 수 있고,
// 빠진 조각을 0 으로 채우면 실제보다 짧은 소요가 일정에 박힌다.
const doorToDoorDurOf = (candidate, departure) => {
  if (!departure) return null;
  const conn = departure.connection;
  const parts = [
    candidate?.accessMin,
    departure.waitMin,
    departure.durationMin,
    ...(conn ? [conn.transferMin, conn.durationMin] : []),
    candidate?.egressMin,
  ];
  return parts.some((v) => v == null)
    ? null
    : parts.reduce((sum, v) => sum + v, 0);
};

// 블록에 반영할 소요·비용 — 시외는 고른 출발편 기준 door-to-door 가 후보 대표값보다
// 정확하다. 계산할 수 없으면(시내 후보·시간표 미적용·조각 누락) 예전대로 편/후보의
// 값을 쓴다. 소요 10분 미만은 카드가 안 잡힌다.
const transitDurOf = (candidate, departure) =>
  Math.max(
    10,
    doorToDoorDurOf(candidate, departure) ??
      departure?.durationMin ??
      candidate?.durationMin ??
      10,
  );
const transitCostOf = (candidate, departure) =>
  departure?.fare ?? departure?.fareOptions?.general ?? candidate?.fare ?? 0;

/**
 * 중복 orderKey 에 견디는 키 생성 (QA: 블록 이동 시 ">=" 오류 픽스).
 * 삭제 복구(원래 키 재사용)·동시 생성 등으로 이웃 블록의 키가 같아질 수 있는데,
 * fractional-indexing 은 before >= after 면 "a1 >= a1" 을 던지고 그게 토스트로
 * 새어 나왔다. 경계가 모순이면 한쪽 경계를 버리고 다시 만든다 — 그 상태에선
 * 상대 순서가 어차피 애매해서 서버의 (order_key, id) 동점 규칙이 순서를 정한다.
 */
const safeKeyBetween = (before, after) => {
  try {
    return generateKeyBetween(before, after);
  } catch {
    try {
      return generateKeyBetween(before, null);
    } catch {
      try {
        return generateKeyBetween(null, after);
      } catch {
        return generateKeyBetween(null, null);
      }
    }
  }
};

/**
 * 최종 목록에서 pos 위치 블록의 양옆 orderKey 경계를 찾는다.
 * auto- 같은 로컬 전용 블록은 서버에 없어 orderKey 가 없으므로 건너뛰고
 * 가장 가까운 서버 블록의 키를 경계로 쓴다. 끝이면 null(개방 경계).
 */
const neighborKeysAround = (finalList, pos, itemsMap) => {
  let before = null;
  for (let i = pos - 1; i >= 0; i -= 1) {
    const id = finalList[i];
    if (isServerBlock(id) && itemsMap[id]?.orderKey != null) {
      before = itemsMap[id].orderKey;
      break;
    }
  }
  let after = null;
  for (let i = pos + 1; i < finalList.length; i += 1) {
    const id = finalList[i];
    if (isServerBlock(id) && itemsMap[id]?.orderKey != null) {
      after = itemsMap[id].orderKey;
      break;
    }
  }
  return [before, after];
};

/**
 * orderKey 정렬 위치에 블록을 삽입한 새 배열 (원격 op 적용용).
 * 로컬 전용 블록(auto- 등, orderKey 없음)은 비교에서 건너뛴다 — 서버 블록들
 * 사이의 상대 위치만 orderKey 가 정하고, 로컬 블록은 제자리를 유지한다.
 * 동점은 id 로 판정한다(ERD: ORDER BY order_key, id).
 */
const insertByOrderKey = (list, itemsMap, block) => {
  const without = list.filter((id) => id !== block.id);
  const at = without.findIndex((id) => {
    const other = itemsMap[id];
    if (!isServerBlock(id) || other?.orderKey == null) return false;
    if (other.orderKey > block.orderKey) return true;
    return (
      other.orderKey === block.orderKey && Number(other.id) > Number(block.id)
    );
  });
  without.splice(at === -1 ? without.length : at, 0, block.id);
  return without;
};

/**
 * 겹침 해소(resolveOverlaps)로 밀린 체인 내 서버 블록들의 시작 오프셋을
 * position PATCH 로 저장한다 — 이웃도 시간축 위치가 바뀐 것이다.
 * 편집(3단계)·이동(5단계)·리사이즈가 공유한다 — 로컬만 밀면 새로고침 때
 * 이웃들이 옛 자리로 되돌아간다(명세 320행: 재계산 저장은 클라이언트 몫).
 * 서버 블록만 보낸다 — 임시 id 는 아직 서버 행이 없고 position 엔드포인트는
 * 비어 있지 않은 orderKey 를 요구한다.
 */
const persistMovedOffsets = (chainIds, prevItems, nextItems, excludeId) => {
  const shifted = (chainIds ?? []).filter(
    (id) =>
      id !== excludeId &&
      isServerBlock(id) &&
      nextItems[id]?.startMins != null &&
      nextItems[id].startMins !== prevItems[id]?.startMins,
  );
  // 순서(orderKey)는 그대로 다시 보낸다 — 바뀐 건 시간축 위치뿐이다
  return Promise.all(
    shifted.map((id) =>
      blockApi.moveBlock(id, {
        startOffsetMinutes: nextItems[id].startMins,
        orderKey: nextItems[id].orderKey,
      }),
    ),
  );
};

/**
 * 보드 위 블록들의 겹침을 해소한다. 시각은 모두 절대 오프셋(Day 1 00:00 기준 분)이라
 * 겹치지 않는 블록은 제자리에 두고(공백 보존), 겹치는 블록만 앞 블록 끝까지 뒤로 민다.
 * fixedId 가 있으면 그 블록만 고정하고 나머지가 비켜난다.
 *
 * boardChain 은 보드 전체다 — Day 별로 나눠 돌리지 않는다. 축이 여행 한 줄이고
 * 오프셋 하나가 Day 와 그 안의 시각을 함께 가리키므로, Day 경계는 해소가 알아야
 * 할 것이 아니다. 자정을 넘겨 이어지는 블록도 그냥 목록 안의 앞 블록이다.
 *
 * 밀림은 Math.max(lastEnd, startMins) 하나로 끝난다 — 겹치지 않는 블록을 만나면
 * 그 블록은 제자리에 남고 lastEnd 가 그 자리로 갱신되므로, 전파가 첫 공백에서
 * 저절로 멈춘다. 밤마다 여덟 시간짜리 공백이 있으니 Day 를 넘는 밀림은 그 Day 가
 * 자정까지 꽉 찼을 때뿐이다.
 */
const resolveOverlaps = (currentItems, boardChain, fixedId) => {
  let newItems = { ...currentItems };
  const others = boardChain.filter((id) => id !== fixedId);
  others.sort((a, b) => newItems[a].startMins - newItems[b].startMins);

  const fixedStart = fixedId ? newItems[fixedId].startMins : -1;
  const fixedEnd = fixedId ? fixedStart + newItems[fixedId].dur : -1;

  // 보드의 바닥은 여행 첫 Day 의 00:00 = 0 이다. Day 별 바닥(자정을 넘어온 블록의
  // 끝)이라는 개념은 없어졌다 — 그 블록이 이미 이 목록 안의 앞 블록이라 lastEnd 가
  // 같은 일을 Day 경계 없이 한다.
  let lastEnd = 0;

  others.forEach((id) => {
    let start = Math.max(lastEnd, newItems[id].startMins);
    if (fixedId) {
      let end = start + newItems[id].dur;
      if (start < fixedEnd && end > fixedStart)
        start = Math.max(start, fixedEnd);
    }
    newItems[id] = { ...newItems[id], startMins: start };
    lastEnd = start + newItems[id].dur;
  });

  const newChain = [...boardChain].sort(
    (a, b) => newItems[a].startMins - newItems[b].startMins,
  );
  return { newItems, newChain };
};

/**
 * 그 Day 의 가장 이른 시작 오프셋(절대 분). 배치된 블록이 없으면 null.
 * "그 Day 로 스크롤한다"가 어디로 가야 하는지를 정하는 값이다 — Day 00:00 은
 * 텅 빈 새벽이라 첫 블록이 있으면 그쪽이 먼저다.
 * 보드 목록이 오프셋 순이므로 그 Day 의 첫 항목이 곧 가장 이른 블록이다.
 */
const firstStartOf = (board, items, dayKey) => {
  const first = blocksOfDay(board, items, dayKey)[0];
  return first == null ? null : items[first].startMins;
};

/**
 * 다이어리 책갈피 모양의 Day 탭.
 * 모양(리본 노치·보드 아래로 끼워지는 느낌)은 CSS(.day-tab)에서 만들고 여기서는
 * 책갈피에 적히는 세 줄 — Day 번호 / 날짜 / 블록 수 — 만 담는다.
 */
function DayTab({ label, date, count, isActive, onClick, viewers = [] }) {
  return (
    <button
      className={`day-tab ${isActive ? "on" : ""}`}
      onClick={onClick}
      aria-current={isActive ? "true" : undefined}
    >
      <span className="dt-top">
        <span className="dt-label">{label}</span>
        <span className="cnt">{count}</span>
      </span>
      {date && <span className="dt-date">{date}</span>}
      {/* 이 Day 를 지금 보고 있는 멤버들 — 프로필 아바타, 테두리는 커서와 같은
          멤버 색 (7단계). 이미지가 없으면 닉네임 첫 글자로 대신한다 */}
      {viewers.length > 0 && (
        <span
          className="dt-viewers"
          title={`보는 중: ${viewers.map((v) => v.name).join(", ")}`}
        >
          {viewers.slice(0, 3).map((v) =>
            v.profileImg?.startsWith("http") ? (
              <img
                key={v.id}
                src={v.profileImg}
                alt={v.name}
                style={{ "--vh": hueOf(v.id) }}
              />
            ) : (
              <i key={v.id} style={{ "--vh": hueOf(v.id) }}>
                {v.name[0]}
              </i>
            ),
          )}
          {viewers.length > 3 && (
            <em className="dt-viewers-more">+{viewers.length - 3}</em>
          )}
        </span>
      )}
    </button>
  );
}

function TimelineCard({
  id,
  item,
  startMins,
  endMins,
  resizingState,
  onResizeStart,
  timelineStart,
  boundTop,
  onEditBlock,
  lockedBy,
  editor,
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id,
    data: { from: "timeline" },
  });
  const catStyle = catOf(item);
  const height = (item?.dur || 30) * PX;
  // 짧은 블록은 내용이 높이를 넘쳐 글이 잘린다 — 높이에 맞춰 단계적으로 접는다.
  // 전체 레이아웃(첫 줄+주소+소요)이 필요한 높이는 약 90px(패딩 20+줄 3개) —
  // 그보다 낮으면 아래 줄부터 순서대로 접는다. 잘린 채 그리는 구간이 없어야 한다.
  const sizeClass = [
    height < 92 && "hide-ctl", // "소요 n분" 줄부터 접는다
    height < 60 && "is-short", // 주소 줄까지
    height <= 34 && "is-tiny", // 한 줄 축약
  ]
    .filter(Boolean)
    .join(" ");
  const isThisResizing =
    resizingState?.id === id ? resizingState.direction : null;

  const handleEdgeClick = (e, direction) => {
    if (!resizingState) {
      e.stopPropagation();
      e.preventDefault();
      onResizeStart(id, direction, e.clientY, item.dur, startMins, boundTop);
    }
  };

  // 위치·높이는 시간 계산 결과라 인라인으로 남기고, 색·모양은 CSS(.slot/.card)가 쥔다.
  const topPx = (startMins - timelineStart) * PX;
  const slotStyle = {
    "--dc": catStyle.hex,
    "--cb": catStyle.bg,
    top: `${topPx}px`,
    height: `${height}px`,
  };

  return (
    <div
      className={`slot ${sizeClass} ${isDragging ? "is-dragging" : ""} ${isThisResizing ? "is-resizing" : ""}`}
      style={slotStyle}
    >
      <span className="tlab">{fmtTime(startMins)}</span>
      <span className="dot" />
      {/* 카드(.card)는 overflow:hidden 이라 모서리 배지는 slot 레벨에 둔다 */}
      <BlockEditorBadge editor={editor} />
      <div
        ref={setNodeRef}
        className={`card ${item?.auto ? "auto-block" : ""}`}
        {...(!isThisResizing ? attributes : {})}
        {...(!isThisResizing ? listeners : {})}
        // 💡 박스 전체 영역에 클릭 이벤트 연결 (드래그나 리사이즈 중이 아닐 때만 동작)
        onClick={() => {
          if (!isThisResizing && onEditBlock) {
            onEditBlock(id);
          }
        }}
      >
        <CardBody
          id={id}
          item={item}
          mode="timeline"
          startMins={startMins}
          endMins={endMins}
          isThisResizing={isThisResizing}
          onEdge={handleEdgeClick}
          onEditBlock={onEditBlock}
          lockedBy={lockedBy}
        />
        {!isThisResizing && (
          <>
            <BlockEditBadge onEdit={onEditBlock && (() => onEditBlock(id))} />
            <BlockLinkBadge item={item} />
          </>
        )}
      </div>
    </div>
  );
}

export function DashboardPage() {
  const { groupId } = useParams();
  // 라우트 파라미터는 문자열 — 서버의 숫자 ID와 맞추려면 변환이 필요하다 (GroupPage 와 동일)
  const projectId = Number(useParams().projectId);
  const navigate = useNavigate();
  const showToast = useToastStore((s) => s.show);

  // 스냅샷은 훅이 소유한다(1단계 — 읽기 연동). 아래 items/pool 편집 상태는
  // 아직 로컬이다: 드래그·수정 결과의 서버 저장은 2~5단계 mutation 에서 붙는다.
  // 그래서 지금 구조는 "로딩 완료 시 서버 보드를 로컬 상태로 시드"이며,
  // 새로고침하면 서버 상태로 되돌아간다(로컬 편집은 아직 휘발).
  const {
    project,
    members: serverMembers,
    items: serverItems,
    pool: serverPool,
    status,
    error,
    reload,
    lastSeq,
  } = useDashboard(projectId);

  // ── 실시간 op 파이프라인: 수신(useProjectOps) → 순서 보장(시퀀서) → 화면 적용 ──
  // 시퀀서·적용 함수는 아래(상태 선언 이후)에 정의되고, 여기서는 수신만 물린다.
  // presence(접속·편집 배지)는 seq 없는 휘발 정보라 시퀀서를 거치지 않고 바로 적용한다.
  const sequencerRef = useRef(null);
  const applyRemoteOpRef = useRef(() => {});
  const applyPresenceRef = useRef(() => {});
  const pendingRemoteOpsRef = useRef([]);
  const interactingRef = useRef(false);
  // 커서 위치 수신은 대시보드 상태를 거치지 않고 RemoteCursorLayer(타임라인·페이지
  // 두 장)로 직행한다 — 초당 수십 건이 보드 전체를 리렌더하지 않도록 성능을 격리.
  // 어느 레이어 소관인지는 각 레이어가 메시지의 area 로 스스로 판단한다.
  const tlCursorHandlerRef = useRef(() => {});
  const pageCursorHandlerRef = useRef(() => {});
  const registerTlCursorHandler = useCallback((fn) => {
    tlCursorHandlerRef.current = fn;
  }, []);
  const registerPageCursorHandler = useCallback((fn) => {
    pageCursorHandlerRef.current = fn;
  }, []);
  const applyCursorRef = useRef(() => {});
  // 보이스 시그널도 ref 우회 — 훅(useVoiceChat)이 아래에서 핸들러를 등록한다
  const voiceSignalRef = useRef(() => {});
  const registerVoiceSignalHandler = useCallback((fn) => {
    voiceSignalRef.current = fn;
  }, []);

  const { sendCursor, sendVoiceSignal } = useProjectOps(projectId, {
    onOp: (op) => sequencerRef.current?.push(op),
    onPresence: (msg) => applyPresenceRef.current(msg),
    onCursor: (msg) => applyCursorRef.current(msg),
    onVoiceSignal: (msg) => voiceSignalRef.current(msg),
  });

  // 상단바(개인 페이지 › 그룹명 › 프로젝트명)의 그룹명·멤버 아바타는 그룹 상세에서,
  // 접속자 표시는 로그인 사용자에서 얻는다. 프로젝트 데이터(이름·기간·예산)의
  // 원천은 위 스냅샷 하나다 — 두 소스가 섞이면 날짜 수정 직후 값이 어긋난다.
  const numericGroupId = Number(groupId);
  const { group } = useGroupDetail(numericGroupId);
  // 제목 옆 ✎(프로젝트 수정 모달)의 저장 경로로만 쓴다 — 저장 후 reload() 로
  // 스냅샷을 다시 읽어야 이 화면의 제목·Day 탭·날짜가 따라 바뀐다.
  const { updateProject } = useProjects(numericGroupId);
  const currentUser = useAuthStore((s) => s.currentUser);

  // 여행 기간이 곧 Day 개수다. 기간이 바뀌면(날짜 캘린더·그룹 페이지 수정 후 재조회)
  // 탭도 함께 바뀐다.
  const dayKeys = useMemo(() => dayKeysOf(project), [project]);

  // 모바일은 읽기 전용이다 — 편집이 드래그·리사이즈에 기대고 있어 좁은 터치
  // 화면에서는 쓸 수 없다. 상태를 모바일에서 강제로 덮어써서, 창을 좁히는
  // 도중에 편집 모드가 남아 있는 경우까지 함께 막는다(초기값만 바꾸면 샌다).
  const isMobile = useIsMobile();
  const [selectedViewMode, setViewMode] = useState("edit");
  const viewMode = isMobile ? "read" : selectedViewMode;
  // 보이스 아이콘 펼침 여부. 기본은 접힘 — 평소엔 하단의 작은 타원 토글만 두고,
  // 누를 때만 마이크·스피커 아이콘이 나온다(보드를 가리지 않게).
  const [voiceOpen, setVoiceOpen] = useState(false);
  const [editProjectOpen, setEditProjectOpen] = useState(false); // 프로젝트 수정 모달
  // 활성 Day 는 이제 탭 클릭이 아니라 **스크롤 위치**가 정한다 — 축이 여행 전체
  // 한 줄이고 모든 Day 의 카드가 동시에 살아 있으므로, "보고 있는 Day"는 뷰포트를
  // 가장 많이 차지하는 Day 다(아래 dominantDayOf). 이름은 activeDay 그대로 둔다:
  // 값의 출처만 바뀌었고 소비처(탭 하이라이트·presence·커서 dayNo·지도)는 그대로
  // 이 값을 읽는다. 탭 클릭은 값을 바꾸는 대신 그 Day 로 스크롤한다(jumpToDay).
  const [scrolledDay, setScrolledDay] = useState("d1");
  // 기간이 줄어 보고 있던 Day 가 사라지면 첫째 날을 본다 — 상태를 되돌리지 않고
  // 렌더 시점에 정하므로 "없는 Day 를 가리키는 한 프레임"이 생기지 않는다.
  const activeDay = dayKeys.includes(scrolledDay) ? scrolledDay : dayKeys[0];

  // 타임라인 좌표계 = 블록의 startOffsetMinutes 와 같은 공간(Day 1 00:00 기준 절대 분).
  // 축은 여행 전체다 — 첫 Day 의 00:00 부터 마지막 Day 의 24:00 까지 한 줄로 잇는다.
  // 렌더 식은 전부 (값 - timelineStart) * PX 꼴 그대로 두고 기준선만 0 으로 내렸다.
  // dayKeysOf 는 항상 Day 를 하나 이상 주므로 activeDay 는 언제나 "dN" 이다.
  // dayBase/dayEnd 는 좌표가 아니라 "활성 Day 의 창"이다 — 겹침 해소(resolveOverlaps)와
  // 자정을 넘어온 블록의 띠(.tl-spill)처럼 아직 Day 단위인 것들만 쓴다.
  const dayBase = (dayNoOf(activeDay) - 1) * blockApi.MINUTES_PER_DAY;
  const dayEnd = dayBase + blockApi.MINUTES_PER_DAY;
  const timelineStart = 0;
  const timelineEnd = dayKeys.length * blockApi.MINUTES_PER_DAY;

  // 보드 편집 상태 — 초기값은 비워 두고, 스냅샷이 도착하면 아래 시드 effect 가 채운다.
  const [items, setItems] = useState({});
  const [pool, setPool] = useState([]);

  // 보드(시간축에 올라간 블록들, 오프셋 순) — 상태가 아니라 items 에서 파생한다.
  // 소속의 근거는 오프셋 하나뿐이다: startMins 가 있으면 보드, 없으면 후보(POOL).
  // 따로 목록을 들고 있으면 "오프셋이 가리키는 Day"와 "들어 있는 목록"이 갈라져,
  // 자정 너머로 밀린 블록이 조용히 사라지거나 저장되지 않는 상태가 생긴다.
  // Day 별로 필요한 곳은 blocksOfDay 선택자로 얻는다.
  const board = useMemo(() => boardOf(items), [items]);

  // ── 함께 있는 느낌 (6단계) ───────────────────────────
  // members: 스냅샷이 시드하고 MEMBER_JOINED/LEFT op 가 갱신한다 (상단바 아바타).
  // onlineIds: 시드는 members[].online, 이후는 PRESENCE 메시지 — 초록 점의 진실.
  // detailLocks: blockId → 락 소유 memberId. DETAIL_LOCK 메시지로만 갱신되는 휘발
  //   정보라 새로고침하면 기존 락은 다음 획득/해제 전까지 안 보인다(advisory 라 감수).
  const [boardMembers, setBoardMembers] = useState([]);
  const [onlineIds, setOnlineIds] = useState(() => new Set());
  const [detailLocks, setDetailLocks] = useState({});
  // "누가 어느 Day 를 보는 중인가" (Day 탭 점 표시, 7단계) — 커서 메시지의 dayNo 로
  // 유지된다. Day 가 바뀔 때만 setState 하므로 초당 수십 건의 커서 트래픽이 보드
  // 리렌더로 이어지지 않는다 — 커서 위치는 레이어 소관, 여기는 Day 만.
  const [viewingDays, setViewingDays] = useState({}); // actorId → dayNo
  const cursorLastSeenRef = useRef({}); // actorId → ts (하트비트 만료 판정)
  // 블록별 "가장 최근 수정자"의 세션 오버레이 — 블록 op(생성·필드수정·이동)의
  // actorId 로 기록한다. 영속 값은 서버의 block.lastEditedById 이고(PRS-04), 이 맵은
  // op 가 도착한 순간 재조회 없이 배지를 갱신하려는 용도 + 아직 서버 id 를 못 받은
  // 낙관적 생성 블록을 덮는 용도다. 시드마다 비운다 — 스냅샷이 더 최신이다
  // (우선순위는 editorBadgeOf 참조).
  const [lastEditors, setLastEditors] = useState({}); // blockId → memberId

  const recordBlockEditor = (blockId, memberId) => {
    if (blockId == null || memberId == null) return;
    setLastEditors((prev) =>
      prev[blockId] === memberId ? prev : { ...prev, [blockId]: memberId },
    );
  };

  const applyPresenceMessage = (msg) => {
    if (msg?.type === "PRESENCE") {
      setOnlineIds((prev) => {
        if (prev.has(msg.memberId) === !!msg.online) return prev; // 변화 없음
        const next = new Set(prev);
        if (msg.online) next.add(msg.memberId);
        else next.delete(msg.memberId);
        return next;
      });
      // 락 만료(TTL)는 브로드캐스트가 없다 — 편집자가 해제 없이 사라지면(브라우저
      // 강제 종료 등) 배지가 남는다. 이탈 신호를 만료의 근사로 삼아 그의 배지를
      // 걷는다. 실제 락도 하트비트가 끊겨 30초 내 만료된다.
      if (!msg.online) {
        setDetailLocks((prev) => {
          const entries = Object.entries(prev).filter(
            ([, holder]) => holder !== msg.memberId,
          );
          return entries.length === Object.keys(prev).length
            ? prev
            : Object.fromEntries(entries);
        });
        // Day 탭의 "보는 중" 점도 함께 걷는다 — 하트비트 만료(12초)보다 빠르다
        setViewingDays((prev) => {
          if (!(msg.memberId in prev)) return prev;
          const next = { ...prev };
          delete next[msg.memberId];
          return next;
        });
      }
    } else if (msg?.type === "DETAIL_LOCK") {
      setDetailLocks((prev) => {
        if (msg.locked) return { ...prev, [msg.blockId]: msg.memberId };
        // 해제는 소유자 것만 지운다 — 늦게 도착한 옛 소유자의 해제가
        // 새 소유자의 배지를 지우면 안 된다
        if (prev[msg.blockId] !== msg.memberId) return prev;
        const next = { ...prev };
        delete next[msg.blockId];
        return next;
      });
    }
  };
  useEffect(() => {
    applyPresenceRef.current = applyPresenceMessage;
  });

  // ── 보이스 (풀 메시 P2P) — 대시보드 입장 = 연결, 로스터 = presence ──
  const voice = useVoiceChat({
    myId: currentUser?.id,
    onlineIds,
    sendVoiceSignal,
    registerSignalHandler: registerVoiceSignalHandler,
    // 모바일은 보이스를 아예 안 쓴다 — 위젯만 감추면 마이크 권한 팝업과
    // P2P 연결은 그대로 돌아간다. 연결 자체를 끊어야 배터리·데이터도 아낀다.
    enabled: !isMobile,
  });

  // 펼쳐 둔 아이콘은 Esc 로도 접는다
  useEffect(() => {
    if (!voiceOpen) return;
    const onKeyDown = (e) => {
      if (e.key === "Escape") setVoiceOpen(false);
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [voiceOpen]);

  // ── 커서 메시지 라우팅 — 위치는 두 레이어로, dayNo 는 viewingDays 로 ──
  // 모든 커서 메시지에 발신자가 보는 dayNo 가 실려 온다(마우스가 멈춰 있어도
  // 5초 주기 view 하트비트가 유지).
  const applyCursorMessage = (msg) => {
    if (msg?.actorId == null) return;
    tlCursorHandlerRef.current(msg);
    pageCursorHandlerRef.current(msg);
    // 내 Day 는 표시하지 않는다 — 내가 보는 탭이 곧 내 위치다
    if (msg.actorId === currentUser?.id) return;
    if (msg.dayNo == null) return;
    cursorLastSeenRef.current[msg.actorId] = Date.now();
    setViewingDays((prev) =>
      prev[msg.actorId] === msg.dayNo
        ? prev
        : { ...prev, [msg.actorId]: msg.dayNo },
    );
  };
  useEffect(() => {
    applyCursorRef.current = applyCursorMessage;
  });

  // 하트비트(5초)가 두 번 유실되면 떠난 것으로 본다 — 잔점 방지
  useEffect(() => {
    const timer = setInterval(() => {
      const now = Date.now();
      setViewingDays((prev) => {
        const alive = Object.keys(prev).filter(
          (id) => now - (cursorLastSeenRef.current[id] ?? 0) < 12_000,
        );
        return alive.length === Object.keys(prev).length
          ? prev
          : Object.fromEntries(alive.map((id) => [id, prev[id]]));
      });
    }, 5000);
    return () => clearInterval(timer);
  }, []);

  // 내가 보는 Day 를 알린다 — Day 전환 즉시 + 5초 주기(마우스가 안 움직여도 유지)
  useEffect(() => {
    const dayNo = dayNoOf(activeDay);
    sendCursor({ area: "view", dayNo });
    const timer = setInterval(() => sendCursor({ area: "view", dayNo }), 5000);
    return () => clearInterval(timer);
  }, [activeDay, sendCursor]);

  // (기간이 줄어 범위를 벗어난 블록을 후보 목록으로 되돌리는 일은 서버가 한다 —
  //  PATCH /projects 가 그 블록들을 POOL 로 옮기고 movedToPool 로 알려준다. 기간이
  //  바뀌는 경로는 전부 reload() 로 스냅샷을 다시 읽으므로 로컬에서 한 벌 더 감사할
  //  것이 없다. 예전의 로컬 감사는 "여행 기간 밖 오프셋"을 전부 잡아내서, 겹침
  //  해소로 마지막 자정 너머까지 밀린 블록까지 후보로 끌어내리고 있었다.)

  const [editingBlockId, setEditingBlockId] = useState(null);
  const [activeId, setActiveId] = useState(null);
  const [resizingState, setResizingState] = useState(null);
  const [dragPreview, setDragPreview] = useState(null);
  const [isGeneratingTransport, setIsGeneratingTransport] = useState(false);

  const {
    initMapOnContainer,
    searchKeyword,
    setSearchKeyword,
    searchResults,
    searchListRef,
    handleSearchPlace,
    handleClearSearch,
    handlePlaceClick,
    focusPlace,
    getMapBounds,
    pinPickMode,
    startPinPick,
    cancelPinPick,
  } = useKakaoMap({ board, items, activeDay, showToast });

  // 지도에서 찍어 온 위치 — 폼이 prop 으로 읽어 좌표·주소만 갈아끼운다.
  // 지정할 때마다 새 객체가 되므로 폼이 "새로 찍었다"를 객체 정체성으로 안다.
  const [pinnedLocation, setPinnedLocation] = useState(null);

  const handleRequestPinPick = useCallback(async () => {
    const picked = await startPinPick();
    if (!picked) return; // Esc·취소·연타로 밀려난 앞선 요청
    setPinnedLocation(picked);
  }, [startPinPick]);

  // 모달이 닫히거나 다른 블록으로 갈아타면 찍어 둔 위치와 지정 모드를 걷는다 —
  // 남겨 두면 다음에 여는 블록에 엉뚱한 좌표가 스며들고, 임시 핀도 지도에 남는다.
  useEffect(() => {
    if (!editingBlockId) return undefined;
    return () => {
      setPinnedLocation(null);
      cancelPinPick();
    };
  }, [editingBlockId, cancelPinPick]);

  // (시작 지점 블록은 이제 프로젝트 생성 모달에서 출발지점을 고를 때 함께
  //  만들어진다 — 입장 시 지오코딩하던 부트스트랩은 실패·동시 입장 중복의
  //  여지가 있어 생성 시점으로 옮기며 제거했다. CreateProjectModal 참조.)

  // 상세 모달을 열면서 지도도 그 장소로 옮긴다 — 모달이 화면을 덮지만 카메라는
  // 그동안 옮겨져 있어, 닫는 즉시 그 장소가 보인다.
  const openBlockDetail = useCallback(
    (id) => {
      setEditingBlockId(id);
      focusPlace(items[id]);
    },
    [focusPlace, items],
  );

  // 저장 실패 시 롤백 — "어디서 왔는지"를 복원하는 대신 서버 진실로 보드를
  // 다시 시드한다. 5.5단계 이후엔 교통 블록까지 전부 서버에 있으므로
  // reload 로 잃는 것이 없다.
  // (useBudget 이 목표 예산 저장 실패에 이걸 쓰므로 훅 호출보다 위에 있어야 한다)
  const rollbackToServer = useCallback(
    (e) => {
      showToast(
        e?.message ?? "변경을 저장하지 못했어요. 서버 상태로 되돌립니다.",
      );
      reload();
    },
    [showToast, reload],
  );

  const {
    headcount,
    totalBudget,
    perPersonBudget,
    targetBudget,
    setTargetBudget,
    budgetDraft,
    setBudgetDraft,
    commitBudgetDraft,
    budgetEditCancelledRef,
    bumpTargetBudget,
    budgetPct,
    remainingBudget,
    budgetSegments,
  } = useBudget({ projectId, project, board, items, rollbackToServer });

  // ── 스냅샷 → 로컬 보드 시드 ──────────────────────────
  // effect 가 아니라 "렌더 중 조건부 setState"(React 공식 파생 상태 리셋 패턴)를 쓴다.
  // 시드가 커밋 전에 반영되므로 빈 보드가 한 프레임 그려지는 일이 없고,
  // effect 내 setState 를 금지하는 lint 규칙(set-state-in-effect)과도 맞는다.
  // serverItems 는 스냅샷이 바뀔 때만 참조가 바뀌므로(useDashboard 의 useMemo)
  // 이 블록은 로딩·재조회 완료 렌더에서 한 번만 실행되고, 로컬 편집을 덮어쓰지 않는다.
  const [seededFrom, setSeededFrom] = useState(null);
  if (status === "loaded" && seededFrom !== serverItems) {
    setSeededFrom(serverItems);

    setItems(serverItems);
    setPool(serverPool);

    // 수정자 오버레이는 시드마다 버린다 — 스냅샷의 lastEditedById 가 서버 확정값이라
    // 끊겨 있던 사이 남이 고친 블록에 내 세션의 옛 기록이 남아 이기면 안 된다.
    setLastEditors({});

    // 멤버·접속 상태의 시드 — 이후는 MEMBER_JOINED/LEFT op 와 PRESENCE 메시지가
    // 이어받는다. 스냅샷의 online 이 실값(서버 PresenceRegistry)이라 그대로 믿는다.
    setBoardMembers(serverMembers);
    setOnlineIds(
      new Set(
        serverMembers.filter((m) => m.online).map((m) => m.memberId),
      ),
    );


    // 다른 프로젝트에서 넘어온 경우 이전 프로젝트의 Day 탭이 남지 않게 한다
    if (!dayKeys.includes(scrolledDay)) setScrolledDay("d1");

    // 목표 예산은 스냅샷의 project 에 실려 오고, 수정은 PATCH /projects 로 저장된다
    // (백엔드 합의로 targetBudget 필드 추가 — useBudget 참조).
    setTargetBudget(project?.targetBudget ?? 0);
  }

  // 없는 프로젝트·비멤버·잘못된 URL 이면 그룹 페이지로 되돌린다 (GroupPage 와 같은 규칙)
  useEffect(() => {
    if (status !== "error") return;
    showToast(error?.message ?? "프로젝트를 열 수 없어요.");
    navigate(`/groups/${groupId}`, { replace: true });
  }, [status, error, groupId, navigate, showToast]);

  // 임시 id 로 만든 로컬 블록을 서버 blockId 로 교체한다 (items + pool).
  // 보드 목록은 items 에서 파생하므로 따로 갈아끼울 것이 없다.
  // 생성 요청이 도는 사이 사용자가 블록을 지웠으면(items 에 없음) 조용히 무시한다.
  const adoptServerId = useCallback((tempId, blockId, extra) => {
    setItems((prev) => {
      if (!prev[tempId]) return prev;
      const next = { ...prev };
      next[blockId] = { ...next[tempId], ...extra, id: blockId };
      delete next[tempId];
      return next;
    });
    setPool((prev) => prev.map((id) => (id === tempId ? blockId : id)));
  }, []);

  // ── Day 전체 자동 생성 = 두 단계: ① 전 구간 후보 조회 → 통합 모달,
  //    ② 구간별 선택 적용 → 일괄 생성 ──
  // choices: "from-to" → 선택한 후보(null = 그 구간 제외)
  const [bulkTransitPicker, setBulkTransitPicker] = useState(null); // {dayKey, segments, choices}

  const regenerateAutoTransport = useCallback(
    async (dayKey) => {
      if (isGeneratingTransport || bulkTransitPicker) return;
      const dayIds = blocksOfDay(board, items, dayKey);
      // 서버 계산 대상 = 그 Day 의 실블록(서버 id 보유)만. 저장 중(임시 id)·자동 생성분 제외
      const realIds = dayIds.filter(
        (id) => !items[id]?.auto && isServerBlock(id),
      );
      if (realIds.length < 2) return;

      setIsGeneratingTransport(true);
      try {
        // 모든 연속 구간의 후보를 한 번의 호출로 받는다.
        // 서버는 블록을 만들지 않는다 — 생성은 모달에서 적용을 눌러야(confirmBulkTransit).
        // 출발편 기준 시각은 서버가 구간마다 from 블록의 시각에서 직접 구한다(응답의 referenceAt).
        const { segments = [] } = await blockApi.calculateTransitCandidates(
          projectId,
          realIds,
        );
        if (!segments.some((s) => s.candidates?.some((c) => c.status === "OK"))) {
          showToast("이동 가능한 경로를 찾지 못했어요.");
          return;
        }
        // 구간별 초기 선택 = 서버 추천(defaultMode) → 첫 이용 가능 후보 → 제외(null)
        // defaultMode 가 null 이면(교통수단 선호 둘 다 선택) 자동 선택하지 않는다 —
        // choices 에 키를 아예 넣지 않는다(= "제외"와 구분되는 "미선택" 상태),
        // 사용자가 카드에서 직접 골라야 한다.
        // choices[pairKey] = {candidate, departure} | null(제외) | undefined(미선택)
        // — departure 는 시외에서 고른 편(시내면 null), candidate 의 첫 편으로 초기화한다.
        const choices = {};
        segments.forEach((s) => {
          if (s.defaultMode == null) return;
          const initial =
            s.candidates?.find(
              (c) => c.mode === s.defaultMode && c.status === "OK",
            ) ??
            s.candidates?.find((c) => c.status === "OK") ??
            null;
          choices[`${s.fromBlockId}-${s.toBlockId}`] = initial
            ? { candidate: initial, departure: initial.departures?.[0] ?? null }
            : null;
        });
        setBulkTransitPicker({ dayKey, segments, choices });
      } catch (e) {
        showToast(
          e?.message ?? "이동수단을 계산하지 못했어요. 잠시 후 다시 시도해주세요.",
        );
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [
      isGeneratingTransport,
      bulkTransitPicker,
      board,
      items,
      projectId,
      showToast,
    ],
  );

  // 통합 모달에서 구간 하나의 선택을 바꾼다 (choice = null 이면 그 구간 제외)
  const setBulkChoice = (pairKey, choice) => {
    setBulkTransitPicker((prev) =>
      prev ? { ...prev, choices: { ...prev.choices, [pairKey]: choice } } : prev,
    );
  };

  // "적용" — 구간별 선택대로 이 Day 의 교통 블록을 일괄 재생성한다.
  // 재생성 = 기존 자동 생성분을 지우고 새로 만든다. 삭제 대상은 체인 소속으로
  // 한정한다 — 팀원이 직접 만든 교통 블록(auto 아님)은 건드리지 않는다.
  const confirmBulkTransit = useCallback(
    async () => {
      const picker = bulkTransitPicker;
      if (!picker) return;
      const { dayKey, choices, segments } = picker;
      const segmentOf = (fromId, toId) =>
        segments.find((s) => s.fromBlockId === fromId && s.toBlockId === toId);

      // 모달이 열린 사이 보드가 바뀌었을 수 있다(협업) — 지금 보드를 기준으로
      // 다시 훑고, 더 이상 인접하지 않은 구간의 선택은 자연히 버려진다(pair 키 불일치)
      const dayIds = blocksOfDay(board, items, dayKey);
      const realIds = dayIds.filter(
        (id) => !items[id]?.auto && isServerBlock(id),
      );
      const oldAutoIds = dayIds.filter((id) => items[id]?.auto);
      if (realIds.length < 2) {
        setBulkTransitPicker(null);
        return;
      }

      setIsGeneratingTransport(true);
      try {
        let newItems = { ...items };
        oldAutoIds.forEach((id) => delete newItems[id]);

        // 해소는 보드 전체를 훑는다 — 이 Day 의 교통 블록이 뒤를 밀면 다음 Day 의
        // 블록까지 밀릴 수 있으므로 목록도 보드 전체여야 한다. 지운 자동 생성분을
        // 빼고, 새 교통 블록은 앞 블록 바로 뒤에 끼운다 — 같은 시각(앞 블록의 끝)에
        // 놓이는 다음 실블록보다 먼저 와야 그 실블록이 밀린다.
        const rebuilt = board.filter((id) => !oldAutoIds.includes(id));
        const createdLocalIds = [];
        realIds.forEach((id, i) => {
          if (i < realIds.length - 1) {
            const chosen = choices[`${id}-${realIds[i + 1]}`];
            if (chosen?.candidate?.status !== "OK") return; // 제외했거나 모르는 구간 — 만들지 않는다
            const info = {
              mode: chosen.candidate.label || chosen.candidate.mode,
              dur: transitDurOf(chosen.candidate, chosen.departure),
              cost: transitCostOf(chosen.candidate, chosen.departure),
            };
            const newId = `auto-${dayKey}-${id}-${i}`;
            newItems[newId] = {
              id: newId,
              cat: "trans",
              sub: info.mode,
              name: `${newItems[id]?.name || ""} 다음 이동`,
              address: "",
              dur: info.dur,
              cost: info.cost,
              auto: true,
              autoDay: dayKey,
              startMins: newItems[id].startMins + newItems[id].dur,
              transportMeta: buildTransportMeta(
                segmentOf(id, realIds[i + 1]),
                chosen.candidate,
                chosen.departure,
              ),
            };
            rebuilt.splice(rebuilt.indexOf(id) + 1, 0, newId);
            createdLocalIds.push(newId);
          }
        });
        // 만들 것도, 지울 것도 없으면 보드를 건드리지 않는다
        if (createdLocalIds.length === 0 && oldAutoIds.length === 0) {
          setBulkTransitPicker(null);
          return;
        }

        const { newItems: resolvedItems, newChain } = resolveOverlaps(
          newItems,
          rebuilt,
          null,
        );

        setBulkTransitPicker(null);

        // 낙관 적용 — 교통 블록이 뒤를 밀어 24:00 을 넘겨도 그대로 둔다.
        // 절대 오프셋에선 1440 을 넘긴 자리가 곧 다음 Day 다(쪼갤 게 없다).
        setItems(resolvedItems);

        // ── 서버 반영 (5.5단계): 기존 생성분 삭제 → 밀린 실블록 시각 저장 →
        //    새 교통 블록 생성 → 로컬 임시 id 를 서버 blockId 로 교체 ──
        try {
          await Promise.all(
            oldAutoIds
              .filter(isServerBlock)
              .map((id) => blockApi.deleteBlock(id)),
          );

          // 새 교통 블록은 아래에서 만든다 — persistMovedOffsets 의 서버 블록
          // 필터가 로컬 임시 id 를 이미 걸러 준다
          await persistMovedOffsets(newChain, items, resolvedItems, null);

          for (const localId of createdLocalIds) {
            const b = resolvedItems[localId];
            if (!b) continue;
            // 각 교통 블록의 경계는 양옆 실블록 — 아직 로컬인 다른 교통 블록은
            // neighborKeysAround 가 건너뛴다
            const [before, after] = neighborKeysAround(
              newChain,
              newChain.indexOf(localId),
              resolvedItems,
            );
            const orderKey = safeKeyBetween(before, after);
            // transportMeta 는 이미 buildTransportMeta 로 만들어 b 에 실려 있다(...b).
            // adoptServerId 가 extra 로도 받도록 그 값을 그대로 넘긴다.
            const transportMeta = b.transportMeta;
            const created = await blockApi.createBlock(projectId, {
              ...b,
              orderKey,
              transportMeta,
            });
            adoptServerId(localId, created.blockId, {
              orderKey,
              transportMeta,
            });
          }
        } catch (e) {
          rollbackToServer(e);
        }
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [
      bulkTransitPicker,
      board,
      items,
      projectId,
      adoptServerId,
      rollbackToServer,
    ],
  );

  // ── 구간 "이동 추가" = 두 단계: ① 후보 조회 → 선택 모달, ② 선택 → 블록 생성 ──
  // 어떤 수단으로 갈지는 사용자가 고른다 — 서버 추천(defaultMode)은 표시만 한다.
  const [transitPicker, setTransitPicker] = useState(null); // {dayKey, currentId, nextId, segment, defaultMode, candidates, chosenCandidate, chosenDeparture}

  const handleAddSingleTransport = useCallback(
    async (dayKey, currentId, nextId) => {
      if (isGeneratingTransport || transitPicker) return;

      // 보드 소속 판정은 오프셋 하나다 — 시각이 없으면 후보(POOL)라 이을 구간이 없다
      if (items[currentId]?.startMins == null) return;
      // 서버 계산은 서버 블록 id 로만 가능하다 — 저장 중(임시 id)이면 잠시 뒤에
      if (!isServerBlock(currentId) || !isServerBlock(nextId)) {
        showToast("블록 저장이 끝난 뒤 다시 시도해주세요.");
        return;
      }

      setIsGeneratingTransport(true);
      try {
        // 두 블록 사이 한 구간만 계산 — blockIds 에 그 둘만 넘긴다.
        // 출발편 기준 시각은 서버가 from 블록의 시각에서 직접 구한다(응답의 referenceAt).
        const { segments = [] } = await blockApi.calculateTransitCandidates(
          projectId,
          [currentId, nextId],
        );
        const segment = segments[0];
        const candidates = segment?.candidates ?? [];
        if (!candidates.some((c) => c.status === "OK")) {
          showToast("두 장소 사이의 경로를 찾지 못했어요.");
          return;
        }
        // defaultMode 가 null 이면(교통수단 선호 둘 다 선택) 자동 선택하지 않는다 —
        // 사용자가 카드에서 직접 골라야 한다.
        const initialCandidate =
          segment.defaultMode == null
            ? null
            : candidates.find(
                (c) => c.mode === segment.defaultMode && c.status === "OK",
              ) ??
              candidates.find((c) => c.status === "OK") ??
              null;
        // 생성하지 않고 선택 모달을 연다 — 생성은 confirmTransitChoice 가 한다
        setTransitPicker({
          dayKey,
          currentId,
          nextId,
          segment,
          defaultMode: segment.defaultMode,
          candidates,
          chosenCandidate: initialCandidate,
          chosenDeparture: initialCandidate?.departures?.[0] ?? null,
        });
      } catch (e) {
        showToast(
          e?.message ?? "이동수단을 계산하지 못했어요. 잠시 후 다시 시도해주세요.",
        );
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [isGeneratingTransport, transitPicker, items, projectId, showToast],
  );

  // 피커에서 다른 후보/편을 고른다 (아직 생성하지 않는다 — confirmTransitChoice 가 한다)
  const setTransitPickerCandidate = (c) => {
    setTransitPicker((prev) =>
      prev
        ? { ...prev, chosenCandidate: c, chosenDeparture: c.departures?.[0] ?? null }
        : prev,
    );
  };

  // 선택 모달에서 "확인"을 누르면 그 구간에 교통 블록을 만든다 (기존 5.5단계 경로)
  const confirmTransitChoice = useCallback(
    async () => {
      const picker = transitPicker;
      setTransitPicker(null);
      const chosen = picker?.chosenCandidate;
      if (!picker || chosen?.status !== "OK") return;

      const { dayKey, currentId } = picker;
      // 해소는 보드 전체를 훑으므로 목록도 보드 전체다 — 새 교통 블록은 앞 블록
      // 바로 뒤에 끼운다(같은 시각에 놓이는 다음 블록보다 먼저 와야 그쪽이 밀린다).
      const currentChain = [...board];
      const insertIdx = currentChain.indexOf(currentId);
      // 모달이 열린 사이 보드가 바뀌었을 수 있다(협업) — 자리가 사라졌으면 중단
      if (insertIdx === -1 || items[currentId]?.startMins == null) {
        showToast("구간이 바뀌어 추가하지 못했어요. 다시 시도해주세요.");
        return;
      }

      const info = {
        mode: chosen.label || chosen.mode,
        dur: transitDurOf(chosen, picker.chosenDeparture),
        cost: transitCostOf(chosen, picker.chosenDeparture),
      };

      setIsGeneratingTransport(true);
      try {
        const newId = `auto-${dayKey}-${currentId}-${Date.now()}`;

        let newItems = { ...items };
        newItems[newId] = {
          id: newId,
          cat: "trans",
          sub: info.mode,
          name: `${items[currentId]?.name || "이전 장소"} 다음 이동`,
          address: "",
          dur: info.dur,
          cost: info.cost,
          auto: true,
          autoDay: dayKey,
          startMins: items[currentId].startMins + items[currentId].dur,
          transportMeta: buildTransportMeta(
            picker.segment,
            chosen,
            picker.chosenDeparture,
          ),
        };
        currentChain.splice(insertIdx + 1, 0, newId);

        const { newItems: resolvedItems, newChain } = resolveOverlaps(
          newItems,
          currentChain,
          null,
        );

        // 낙관 적용 — 교통 블록이 뒤를 밀어 24:00 을 넘겨도 그대로 둔다.
        // 절대 오프셋에선 1440 을 넘긴 자리가 곧 다음 Day 다(쪼갤 게 없다).
        setItems(resolvedItems);

        // ── 서버 반영 (5.5단계): 밀린 이웃 위치 저장 → 생성 → id 교체 ──
        try {
          // 새 교통 블록은 아래에서 만든다 — persistMovedOffsets 의 서버 블록
          // 필터가 로컬 임시 id 를 이미 걸러 준다
          await persistMovedOffsets(newChain, items, resolvedItems, null);

          const b = resolvedItems[newId];
          const [before, after] = neighborKeysAround(
            newChain,
            newChain.indexOf(newId),
            resolvedItems,
          );
          const orderKey = safeKeyBetween(before, after);
          // transportMeta 는 이미 buildTransportMeta 로 만들어 b 에 실려 있다(...b).
          // adoptServerId 가 extra 로도 받도록 그 값을 그대로 넘긴다.
          const transportMeta = b.transportMeta;
          const created = await blockApi.createBlock(projectId, {
            ...b,
            orderKey,
            transportMeta,
          });
          adoptServerId(newId, created.blockId, {
            orderKey,
            transportMeta,
          });
        } catch (e) {
          rollbackToServer(e);
        }
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [
      transitPicker,
      items,
      board,
      projectId,
      adoptServerId,
      rollbackToServer,
      showToast,
    ],
  );

  const timelineDOMRef = useRef(null);
  const poolDOMRef = useRef(null);
  const pageDOMRef = useRef(null); // 페이지 좌표 커서(area:"page")의 기준 박스
  const activeDragRef = useRef(null);
  const dragRegionRef = useRef(null);

  // 최신 items 를 읽기 위한 latest-ref — 리사이즈 종료 시점과 원격 op 적용이 쓴다.
  // 원격 적용은 한 tick 에 여러 op 를 연달아 처리할 수 있어(시퀀서 drain), 커밋 전에도
  // 서로의 결과를 보도록 적용 함수가 이 ref 를 직접 갱신하며 진행한다.
  const itemsRef = useRef(items);
  useEffect(() => {
    itemsRef.current = items;
  });

  // ── 원격 op 적용 ─────────────────────────────────────
  // 원격 블록을 startMins(절대 오프셋)가 가리키는 자리로 놓는다(생성·이동 공용).
  // 시간축 위 자리는 items 갱신만으로 끝난다 — 보드 목록이 오프셋에서 파생하기
  // 때문이다. 여기서 챙길 것은 후보(POOL) 목록뿐이고, 후보의 순서는 시각이 없어
  // 손 정렬(orderKey)만이 근거다.
  const placeRemoteBlock = (block) => {
    setPool((prev) => {
      const without = prev.filter((id) => id !== block.id);
      return block.startMins == null
        ? insertByOrderKey(without, itemsRef.current, block)
        : without;
    });
  };

  /**
   * 시퀀서가 순서를 맞춰 넘겨준 op 를 화면에 반영한다.
   * 시퀀서는 커서 전진을 위해 자기 op 까지 전부 넘긴다(자기 op 도 seq 를 소비하므로
   * 걸러서 받으면 갭으로 오인한다). 자기 op 를 적용할지는 op 종류별로 여기서 정한다 —
   * 명세(§X-Client-Id)는 낙관 중복 적용을 막으려 일괄 스킵을 권하지만, 그러면
   * 동시 편집에서 마지막 편집자의 화면만 남의 값으로 굳는다. 아래 own 주석 참조.
   */
  const applyOpToBoard = (op) => {
    // 자기 op 를 통째로 버리면 동시 편집에서 "마지막에 놓은 사람"이 진다 —
    // 남의 op 를 내 낙관 상태 위에 덮은 뒤 내 echo 를 스킵하면 남의 값이 최종으로
    // 남는다(서버 DB 는 마지막 쓰기인 내 값인데 화면만 어긋난다).
    // 시퀀서가 seq 순서로 넘겨주고 seq 순서 = 서버가 쓴 순서이므로, 마지막 쓰기가
    // 이기는 op(이동·필드 갱신)는 자기 것도 그대로 적용해 제 위치를 재확정한다.
    // 재적용이 해로운 op 만 아래에서 개별로 스킵한다.
    const own = op.clientId === getClientId();

    const payload = op.payload ?? {};
    switch (op.type) {
      case "BLOCK_CREATED": {
        // 수정자 기록은 own 여부와 무관하다 — 화면 반영을 스킵해도 "누가 만들었나"는 남긴다
        recordBlockEditor(payload.block?.blockId, op.actorId);
        // 자기 생성만은 스킵한다 — POST 응답이 임시 id 를 서버 id 로 바꾸는
        // (adoptServerId) 사이에 echo 가 끼어들면 같은 블록이 두 벌 들어간다.
        if (own) break;
        const block = blockApi.toUiBlock(payload.block);
        itemsRef.current = { ...itemsRef.current, [block.id]: block };
        setItems((prev) => ({ ...prev, [block.id]: block }));
        placeRemoteBlock(block);
        break;
      }
      case "BLOCK_FIELD_UPDATED": {
        recordBlockEditor(payload.blockId, op.actorId);
        // 자기 op 도 적용한다 — 서버가 스테일 필드를 payload 에서 빼고 보내므로
        // (명세: "적용된 필드만 포함") seq 순서대로 덮으면 서버 최종값과 같아진다.
        // 블록의 화면상 y 위치는 startMins 라서, 이게 빠지면 이동을 재확정해도
        // 시각은 남의 값 그대로 남는다.
        const id = payload.blockId;
        const base = itemsRef.current[id];
        if (!base) break; // 모르는 블록(이미 삭제 등) — 무시
        const patch = blockApi.serverFieldsToUiPatch(payload.fields);
        const updated = { ...base, ...patch };
        itemsRef.current = { ...itemsRef.current, [id]: updated };
        setItems((prev) => (prev[id] ? { ...prev, [id]: updated } : prev));
        break;
      }
      case "BLOCK_MOVED": {
        recordBlockEditor(payload.blockId, op.actorId);
        // 자기 op 도 적용한다 — 이동은 마지막 쓰기가 이긴다. 남이 먼저 옮긴 op 에
        // 덮인 자리를 자기 echo 가 제 위치로 되돌려 놓는 것이 이 재적용의 목적이다.
        const base = itemsRef.current[payload.blockId];
        if (!base) {
          if (own) break; // 내가 옮긴 뒤 지운 블록 — 재시드할 이유가 없다
          reload(); // 모르는 블록의 이동 — 로컬이 어긋난 상태라 재시드가 정직하다
          break;
        }
        // 위치와 시각이 같은 정수다 — 전에는 Day 만 먼저 오고 시각이 뒤늦게
        // 필드 op 로 따라와, 원격 화면에 "Day 는 옮겨졌는데 시각은 옛 값"인
        // 창이 열렸다. 서버는 POOL 이동도 명시적 null 로 보낸다(키는 항상 있다).
        const offset = payload.startOffsetMinutes ?? null;
        const moved = {
          ...base,
          startMins: offset,
          orderKey: payload.orderKey,
        };
        itemsRef.current = { ...itemsRef.current, [moved.id]: moved };
        setItems((prev) => (prev[moved.id] ? { ...prev, [moved.id]: moved } : prev));
        placeRemoteBlock(moved);
        break;
      }
      case "BLOCK_DELETED": {
        // 사라진 블록의 수정자 기록도 걷는다 (own 여부 무관)
        setLastEditors((prev) => {
          if (!(payload.blockId in prev)) return prev;
          const next = { ...prev };
          delete next[payload.blockId];
          return next;
        });
        // 자기 삭제는 이미 로컬에서 제거됐다 — 재적용하면 아래 "다른 멤버가
        // 삭제했어요" 토스트가 자기 삭제에 뜬다.
        if (own) break;
        const id = payload.blockId;
        const next = { ...itemsRef.current };
        delete next[id];
        itemsRef.current = next;
        setItems((prev) => {
          const n = { ...prev };
          delete n[id];
          return n;
        });
        setPool((prev) => prev.filter((x) => x !== id));
        if (editingBlockId === id) {
          setEditingBlockId(null);
          showToast("편집 중이던 블록을 다른 멤버가 삭제했어요");
        }
        break;
      }
      case "TARGET_BUDGET_CHANGED":
        if (own) break; // 디바운스 중인 로컬 ± 입력과 싸운다
        setTargetBudget(payload.targetBudget ?? 0);
        break;
      case "PROJECT_UPDATED":
        if (own) break; // 자기 변경으로 보드 전체를 재시드할 이유가 없다
        // 이름·기간·이동수단·movedToPool — Day 탭 수 등 훅 소유 파생에 걸쳐 있어 재시드가 정확하다.
        // reload() 가 스냅샷을 통째로 다시 받아오므로 project.transportPrefs 도 이 한 번으로 갱신된다.
        reload();
        break;
      case "PROJECT_DELETED":
        showToast("이 프로젝트가 삭제됐어요.");
        navigate(`/groups/${groupId}`, { replace: true });
        break;
      case "MEMBER_JOINED":
        // 그룹의 모든 프로젝트 토픽에 발행된다 — 상단바 아바타 목록 갱신.
        // own(자기 가입)도 중복 가드가 있어 재적용이 무해하다.
        setBoardMembers((prev) =>
          prev.some((m) => m.memberId === payload.memberId)
            ? prev
            : [
                ...prev,
                {
                  memberId: payload.memberId,
                  nickname: payload.nickname,
                  profileImg: payload.profileImg || null,
                  online: false, // 접속하면 PRESENCE 가 알려준다
                },
              ],
        );
        break;
      case "MEMBER_LEFT":
        setBoardMembers((prev) =>
          prev.filter((m) => m.memberId !== payload.memberId),
        );
        setOnlineIds((prev) => {
          if (!prev.has(payload.memberId)) return prev;
          const next = new Set(prev);
          next.delete(payload.memberId);
          return next;
        });
        break;
      default:
        // PROJECT_STATUS_CHANGED·BUDGET_HEADCOUNT_CHANGED —
        // 표시 UI 가 없어 seq 만 소비한다 (전방 호환: 모르는 타입도 여기로)
        break;
    }
  };

  // 적용 함수는 렌더마다 새로 만들어지므로 latest-ref 로 시퀀서에 노출한다
  useEffect(() => {
    applyRemoteOpRef.current = applyOpToBoard;
  });

  // 시퀀서 수명 — 프로젝트마다 하나. 드래그·리사이즈 중에는 pending 큐로 우회한다.
  useEffect(() => {
    if (!Number.isInteger(projectId)) return;
    const sequencer = createOpSequencer({
      fetchOpsAfter: (afterSeq) => blockApi.fetchOpsAfter(projectId, afterSeq),
      apply: (op) => {
        if (interactingRef.current) pendingRemoteOpsRef.current.push(op);
        else applyRemoteOpRef.current(op);
      },
    });
    sequencerRef.current = sequencer;
    return () => {
      sequencer.dispose();
      sequencerRef.current = null;
    };
  }, [projectId]);

  // 스냅샷 (재)시드마다 커서를 스냅샷의 lastSeq 로 리셋 — 그 이하 op 는 이미
  // 스냅샷에 반영돼 있고, 그보다 앞서 수신돼 버퍼에 쌓인 op 는 이때 순서대로 적용된다
  useEffect(() => {
    if (status !== "loaded") return;
    sequencerRef.current?.reset(lastSeq);
  }, [status, serverItems, lastSeq]);

  // 드래그·리사이즈 중에는 원격 적용을 미룬다 — 조작 중 체인이 발밑에서 바뀌면
  // 드롭 계산이 꼬이고 잡고 있던 블록이 순간이동한다. 끝나는 즉시 밀린 것을 반영한다.
  useEffect(() => {
    const interacting = activeId != null || resizingState != null;
    interactingRef.current = interacting;
    if (!interacting && pendingRemoteOpsRef.current.length > 0) {
      const ops = pendingRemoteOpsRef.current;
      pendingRemoteOpsRef.current = [];
      ops.forEach((op) => applyRemoteOpRef.current(op));
    }
  }, [activeId, resizingState]);

  /**
   * @param form     폼의 현재 값(서버 필드명)
   * @param baseline 모달을 연 시점의 폼 값 — "사용자가 만진 필드"의 판정 기준.
   *                 지금의 items[targetId] 와 비교하면, 모달이 열린 사이 다른
   *                 멤버가 바꾼 필드까지 내 변경으로 잡혀 옛 값이 함께 전송되고
   *                 서버 LWW(수신 시각)가 그걸 최신으로 받아 남의 변경을 지운다.
   */
  const handleSaveBlock = async (form, baseline) => {
    const targetId = editingBlockId;
    const base = items[targetId];
    if (!base) return;

    // baseline 이 없으면(구 호출부) 지금 값 기준으로 되돌아간다 — 없는 편이 낫지만
    // 최소한 저장이 통째로 막히지는 않게 한다
    const openedWith = baseline ?? {
      name: base.name ?? "",
      detail: base.detail ?? "",
      durationMin: base.dur,
      budget: base.cost,
    };
    // 폼 입력은 문자열로 온다("70") — 숫자 필드는 정규화해서 비교해야
    // "70" !== 70 이 거짓 변경으로 잡히지 않는다
    const numOf = (v) => (v === "" || v == null ? null : Number(v));
    const touched = {
      name: form.name !== openedWith.name,
      detail: form.detail !== openedWith.detail,
      dur: numOf(form.durationMin) !== numOf(openedWith.durationMin),
      cost: numOf(form.budget) !== numOf(openedWith.budget),
    };

    // 폼(서버 필드명) → 화면 블록 필드.
    // cat 은 어댑터 매핑으로 되돌린다 — toLowerCase 는 TRANSPORT→"transport" 가
    // 되어 화면의 "trans" 와 어긋난다(카테고리 왕복 파괴 버그의 원인이었다).
    const merged = {
      ...base,
      name: form.name,
      cat: blockApi.CAT_FROM_SERVER[form.category] ?? base.cat,
      sub: form.subCategory,
      address: form.address,
      // 좌표는 주소 검색(도로명 주소 → 카카오 지오코딩)이 채워 준다. 장소성
      // 카테고리(SPOT·FOOD·STAY)는 서버가 lat/lng 를 필수로 보므로(BLOCK400)
      // 여기서 흘려버리면 커스텀 블록 생성이 통째로 거절된다.
      lat: form.lat ?? base.lat ?? null,
      lng: form.lng ?? base.lng ?? null,
      detail: form.detail,
      dur: form.durationMin ? Number(form.durationMin) : base.dur,
      cost: form.budget ? Number(form.budget) : base.cost,
    };

    // ── 새 블록: 서버에 생성하고 임시 id 를 서버 blockId 로 교체한다 (2단계) ──
    if (isTempId(targetId)) {
      // 풀 맨 앞 배치를 서버에도 그대로 남기기 위해 orderKey 를 클라이언트가 만든다.
      // 미지정으로 보내면 서버가 말단 키를 부여하는데, 응답에 그 키가 없어
      // 로컬이 순서를 알 수 없게 된다.
      const firstKey =
        pool
          .filter((id) => id !== targetId)
          .map((id) => items[id]?.orderKey)
          .find((k) => k != null) ?? null;
      const orderKey = safeKeyBetween(null, firstKey);

      try {
        const created = await blockApi.createBlock(projectId, {
          ...merged,
          startMins: null, // 커스텀 블록은 후보(POOL)로 생성된다
          orderKey,
        });
        // 세부 내용(detail)은 생성 바디에 없다(명세) — 생성 직후 필드 갱신으로 저장
        if (merged.detail) {
          await blockApi.updateBlockFields(created.blockId, {
            detail: merged.detail,
          });
        }

        const saved = { ...merged, id: created.blockId, orderKey };
        setItems((prev) => {
          const next = { ...prev };
          delete next[targetId];
          next[created.blockId] = saved;
          return next;
        });
        setPool((prev) =>
          prev.map((id) => (id === targetId ? created.blockId : id)),
        );
        setEditingBlockId(null);
        showToast("블록이 저장됐어요 ✓");
      } catch (e) {
        // 임시 블록과 모달을 그대로 남겨 재시도할 수 있게 한다
        showToast(
          e?.message ?? "블록을 저장하지 못했어요. 잠시 후 다시 시도해주세요.",
        );
      }
      return;
    }

    // ── 기존 블록: 사용자가 만진 필드만 PATCH /fields 배치로 저장한다 (3단계) ──
    // 판정 기준은 모달 오픈 시점(openedWith)이고, 만지지 않은 필드는 지금 서버
    // 값(base)을 그대로 둔다 — 그래야 모달이 열린 사이 도착한 남의 변경이
    // 전송에서도, 로컬 상태에서도 살아남는다.
    const patched = { ...base };
    if (touched.name) patched.name = form.name;
    if (touched.detail) patched.detail = form.detail;
    if (touched.dur) patched.dur = numOf(form.durationMin) ?? base.dur;
    if (touched.cost) patched.cost = numOf(form.budget) ?? base.cost;

    const changed = {};
    if (touched.name) changed.name = patched.name;
    if (touched.detail) changed.detail = patched.detail;
    if (touched.dur) changed.durationMin = patched.dur;
    if (touched.cost) changed.budget = patched.cost;
    // category·subCategory·address 는 보내지 않는다 — 서버 LWW 화이트리스트
    // (LWW_FIELDS: name·budget·durationMin·detail·isTimeFixed·vehicleFlag·
    // transportMeta)에 없어 BLOCK400_2 로 배치 전체가 거부된다.
    // 셋 다 "생성 시에만" 정하는 값으로 폼에서 잠갔다.
    // 종료 시각은 보내지 않는다 — 시작 오프셋 + 소요에서 파생되는 값이다.

    if (Object.keys(changed).length === 0) {
      setEditingBlockId(null); // 변경 없음 — 요청을 보내지 않는다
      return;
    }

    // 소요시간이 늘면 뒤 이웃이 밀린다 — PATCH 전에 밀린 결과를 미리 계산해 둔다.
    // 24:00 을 넘겨도 그대로 둔다 — 절대 오프셋에선 그 자리가 곧 다음 Day 다.
    // 해소는 보드 전체를 훑는다: 어느 Day 의 카드에서 열었든, 밀림은 Day 경계가
    // 아니라 다음 공백에서 멈춘다. 시각이 없는 후보(POOL)만 해소를 건너뛴다.
    let resolved = null;
    if (base.startMins != null) {
      resolved = resolveOverlaps(
        { ...items, [targetId]: patched },
        board,
        targetId,
      );
    }

    try {
      const result = await blockApi.updateBlockFields(targetId, changed);
      // 1인 모드에서 applied:false(스테일)는 나올 수 없다 — 나오면 그 자체가 조사 대상
      const stale = Object.entries(result?.applied ?? {})
        .filter(([, ok]) => !ok)
        .map(([f]) => f);
      if (stale.length > 0) {
        console.warn("[dashboard] LWW 스테일 필드 (1인 모드에서 비정상):", stale);
      }

      // 로컬 반영. 체인 블록이면 겹침 해소로 밀린 이웃들의 위치도 서버에 저장한다 —
      // 로컬만 밀면 새로고침 때 이웃들이 옛 자리로 되돌아간다(명세 320행의
      // "이동 후 시각 재계산은 클라이언트 몫" 규칙과 같은 경로, 5단계에서 재사용).
      if (resolved) {
        await persistMovedOffsets(
          resolved.newChain,
          items,
          resolved.newItems,
          targetId,
        );

        setItems(resolved.newItems);
      } else {
        setItems({ ...items, [targetId]: patched });
      }

      setEditingBlockId(null);
      showToast("블록이 저장됐어요 ✓");
    } catch (e) {
      // 모달을 열어 둔다 — 재시도하면 같은 diff 가 다시 전송된다(멱등)
      showToast(
        e?.message ?? "블록을 저장하지 못했어요. 잠시 후 다시 시도해주세요.",
      );
    }
  };

  const handleCreateCustomBlock = () => {
    const newId = `custom-${Date.now()}`;
    const newBlock = {
      id: newId,
      cat: "etc",
      sub: "",
      name: "새 일정",
      address: "",
      detail: "",
      dur: 60,
      // 후보(POOL) 블록은 시각 없는 느슨한 블록 — 시각은 체인에 놓일 때 계산된다
      startMins: null,
      lat: null,
      lng: null,
      cost: 0,
      auto: false,
    };
    setItems((prev) => ({ ...prev, [newId]: newBlock }));
    setPool((prev) => [newId, ...prev]);
    setEditingBlockId(newId);
  };

  // 휴지통 드롭 — 서버 블록은 소프트 삭제(tombstone, DELETE /blocks) 후 로컬에서
  // 제거한다(4단계). 서버 확인 전에는 지우지 않는다 — 실패 시 원래 위치로 복원하는
  // 롤백을 관리하는 것보다, 확인까지의 짧은 지연을 감수하는 쪽이 단순하다.
  // 로컬 전용 블록(auto- 교통)은 요청 없이 바로 제거한다.
  const handleDeleteBlock = async (id) => {
    if (isServerBlock(id)) {
      try {
        await blockApi.deleteBlock(id);
      } catch (e) {
        showToast(
          e?.message ?? "블록을 삭제하지 못했어요. 잠시 후 다시 시도해주세요.",
        );
        return; // 블록을 그대로 둔다 — 다시 드래그하면 재시도
      }
    }

    setPool((prev) => prev.filter((x) => x !== id));
    setItems((prev) => {
      const next = { ...prev };
      delete next[id];
      return next;
    });
    showToast("블록을 삭제했어요 🗑");
  };

  // 저장 전에 모달을 닫으면 임시 블록을 남기지 않는다 (서버에도 아직 없다)
  const handleCancelEdit = () => {
    if (isTempId(editingBlockId)) {
      const tempId = editingBlockId;
      setItems((prev) => {
        const next = { ...prev };
        delete next[tempId];
        return next;
      });
      setPool((prev) => prev.filter((id) => id !== tempId));
    }
    setEditingBlockId(null);
  };

  // ── 교통 블록 편집 재선택 — 저장된 candidates 스냅샷으로 피커를 재조회 없이 연다 ──
  // 생성 흐름(transitPicker)과 상태를 공유하지 않는다 — "생성 후 자리에 삽입" 로직과
  // 얽히면 오히려 복잡해진다(계획 Task 7).
  const [transportReselectPicker, setTransportReselectPicker] = useState(null); // {blockId, candidates, chosenCandidate, chosenDeparture}

  const handleReselectTransport = (block) => {
    const candidates = block.transportMeta?.candidates;
    if (!candidates || candidates.length === 0) {
      showToast("다시 계산할 후보가 없어요. 삭제 후 새로 만들어주세요.");
      return;
    }
    const chosenMode = block.transportMeta?.chosen?.mode;
    const initialCandidate =
      candidates.find((c) => c.mode === chosenMode) ?? candidates[0];
    setTransportReselectPicker({
      blockId: block.id,
      candidates,
      chosenCandidate: initialCandidate,
      chosenDeparture: initialCandidate?.departures?.[0] ?? null,
    });
  };

  const setReselectCandidate = (c) => {
    setTransportReselectPicker((prev) =>
      prev
        ? { ...prev, chosenCandidate: c, chosenDeparture: c.departures?.[0] ?? null }
        : prev,
    );
  };

  // "저장" — 같은 블록에서 선택만 바꾼다(재생성 없음).
  // transportMeta 뿐 아니라 소요(durationMin)·종료시각·비용(budget)까지 PATCH 해야
  // 새로고침 후에도 유지된다(예전엔 meta 만 보내 소요·비용이 로컬에만 남았다).
  // 소요가 바뀌면 이웃이 밀린다 — 저장 경로(handleSaveBlock)와 같은
  // 겹침 해소 + 밀린 이웃 위치 저장을 그대로 태운다.
  const applyReselectTransport = useCallback(async () => {
    const picker = transportReselectPicker;
    const chosen = picker?.chosenCandidate;
    if (!picker || chosen?.status !== "OK") return;
    const block = items[picker.blockId];
    if (!block) {
      setTransportReselectPicker(null);
      return; // 모달이 열린 사이 삭제됨(협업)
    }

    const newMeta = {
      ...buildTransportMeta(
        // segment 는 원래 스냅샷의 segment 메타를 그대로 유지 — 재조회하지 않으므로
        // referenceAt 등은 처음 계산 시점 그대로다. 이 segment 조각에는 candidates 가
        // 없어 buildTransportMeta 결과가 빈 배열이 되므로 원래 스냅샷으로 덮어쓴다.
        block.transportMeta?.segment,
        chosen,
        picker.chosenDeparture,
      ),
      candidates: picker.candidates,
    };
    const newDur = transitDurOf(chosen, picker.chosenDeparture);
    const newCost = transitCostOf(chosen, picker.chosenDeparture);
    const merged = { ...block, dur: newDur, cost: newCost, transportMeta: newMeta };

    // 보드 위 블록이면 소요 변경이 이웃을 민다 — 저장 전에 밀린 결과를 계산해 둔다.
    // 24:00 을 넘겨도 그대로 둔다 — 절대 오프셋에선 그 자리가 곧 다음 Day 다.
    let resolved = null;
    if (block.startMins != null) {
      resolved = resolveOverlaps(
        { ...items, [block.id]: merged },
        board,
        block.id,
      );
    }

    setTransportReselectPicker(null);
    // 뒤에 열려 있는 편집 폼도 닫는다 — 폼이 옛 소요·비용을 들고 있어서,
    // 그대로 두면 사용자가 폼 저장을 눌러 방금 바꾼 값을 되돌려버린다
    setEditingBlockId(null);
    try {
      // 종료 시각은 보내지 않는다 — 시작 오프셋 + 소요에서 파생되는 값이다
      await blockApi.updateBlockFields(picker.blockId, {
        durationMin: newDur,
        budget: newCost,
        transportMeta: newMeta,
      });

      if (resolved) {
        await persistMovedOffsets(
          resolved.newChain,
          items,
          resolved.newItems,
          block.id,
        );
        setItems(resolved.newItems);
      } else {
        setItems((prev) =>
          prev[block.id] ? { ...prev, [block.id]: merged } : prev,
        );
      }
      showToast("이동 수단을 바꿨어요 ✓");
    } catch (e) {
      showToast(e?.message ?? "저장하지 못했어요. 잠시 후 다시 시도해주세요.");
    }
  }, [transportReselectPicker, items, board, showToast]);

  // ── 편집 락 수명 = 편집 모달 수명 (6단계, advisory) ──
  // 모달을 열면 획득 → 10초 주기 하트비트(TTL 30초) → 닫으면 해제.
  // 락은 편집을 막지 않는다(서버도 안 막는다) — 다른 멤버 화면에 "편집 중" 배지를
  // 띄우는 신호일 뿐이다. 획득 실패·요청 실패 모두 편집을 계속하게 둔다.
  useEffect(() => {
    if (!editingBlockId || !isServerBlock(editingBlockId)) return undefined;
    const blockId = editingBlockId;
    let heartbeatTimer = null;
    let acquired = false;
    let cancelled = false;

    blockApi
      .acquireDetailLock(blockId)
      .then((r) => {
        if (cancelled) {
          // 응답 전에 모달이 닫혔다 — 방금 얻은 락을 바로 되돌려 준다
          if (r?.acquired) blockApi.releaseDetailLock(blockId).catch(() => {});
          return;
        }
        if (r?.acquired) {
          acquired = true;
          heartbeatTimer = setInterval(() => {
            blockApi.heartbeatDetailLock(blockId).catch(() => {});
          }, 10_000);
        } else if (r?.holder != null) {
          // 남이 잡고 있다 — 배지 상태에 직접 반영한다. 락이 내 구독 이전부터
          // 있었으면 DETAIL_LOCK 메시지를 받은 적이 없어 이 경로가 유일한 단서다.
          setDetailLocks((prev) => ({ ...prev, [blockId]: r.holder }));
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
      if (heartbeatTimer) clearInterval(heartbeatTimer);
      if (acquired) blockApi.releaseDetailLock(blockId).catch(() => {});
    };
  }, [editingBlockId]);

  useEffect(() => {
    dragRegionRef.current = dragPreview?.region;
  }, [dragPreview]);
  useEffect(() => {
    if (!activeId || !timelineDOMRef.current) return;
    const handleWheel = (e) => {
      if (dragRegionRef.current === "timeline") {
        e.preventDefault();
        timelineDOMRef.current.scrollTop += e.deltaY;
      }
    };
    window.addEventListener("wheel", handleWheel, { passive: false });
    return () => window.removeEventListener("wheel", handleWheel);
  }, [activeId]);

  const { setNodeRef: setTimelineDroppable } = useDroppable({
    id: "timelineArea",
  });
  const setTimelineRefs = useCallback(
    (node) => {
      timelineDOMRef.current = node;
      setTimelineDroppable(node);
    },
    [setTimelineDroppable],
  );

  const { setNodeRef: setPoolDroppable } = useDroppable({ id: "poolArea" });
  const setPoolRef = useCallback(
    (node) => {
      poolDOMRef.current = node;
      setPoolDroppable(node);
    },
    [setPoolDroppable],
  );

  const handleResizeStart = useCallback(
    (id, direction, startY, startDur, originalStartMins, boundTop) => {
      // 축이 보드 전체 한 줄이라 화면에 보이는 아무 카드나 리사이즈할 수 있다 —
      // 활성 탭과 무관하고, 겹침 해소도 보드 전체를 훑으므로 여기서 Day 를 뽑아
      // 실어 둘 것이 없다.
      setResizingState({
        id,
        direction,
        startY,
        startDur,
        originalStartMins,
        boundTop,
        originalItems: items,
      });
    },
    [items],
  );

  // 리사이즈 종료 시 결과를 서버에 저장한다 — 리사이즈된 블록(dur·시각)과
  // 겹침 해소로 밀린 이웃들 전부. originalItems(리사이즈 시작 스냅샷) 대비
  // 값이 바뀐 체인 내 서버 블록이 저장 대상이다.
  const persistResize = useCallback(
    async (rs) => {
      const current = itemsRef.current;
      const original = rs.originalItems;
      // 저장 대상은 보드 전체다 — 밀림이 Day 경계에서 멈추지 않으므로 Day 로
      // 좁히면 자정 너머로 밀린 이웃의 위치가 저장되지 않는다. 실제로 값이
      // 바뀐 블록만 걸러 내므로 넓혀도 나가는 요청 수는 그대로다.
      const dirty = boardOf(current).filter(
        (id) =>
          isServerBlock(id) &&
          original[id] &&
          (current[id].startMins !== original[id].startMins ||
            current[id].dur !== original[id].dur),
      );
      if (dirty.length === 0) return;

      try {
        // 소요는 필드 PATCH, 시작 오프셋은 position PATCH 로 나뉜다 —
        // 시각이 위치가 되면서 더 이상 LWW 필드가 아니다
        await Promise.all(
          dirty.flatMap((id) => {
            const b = current[id];
            const calls = [];
            if (b.dur !== original[id].dur) {
              calls.push(blockApi.updateBlockFields(id, { durationMin: b.dur }));
            }
            if (b.startMins !== original[id].startMins) {
              calls.push(
                blockApi.moveBlock(id, {
                  startOffsetMinutes: b.startMins,
                  orderKey: b.orderKey,
                }),
              );
            }
            return calls;
          }),
        );
      } catch (e) {
        showToast(
          e?.message ?? "크기 변경을 저장하지 못했어요. 서버 상태로 되돌립니다.",
        );
        reload();
      }
    },
    [reload, showToast],
  );

  useEffect(() => {
    if (!resizingState) return;
    const handleMouseMove = (e) => {
      const deltaY = e.clientY - resizingState.startY;
      const deltaMins = Math.round(deltaY / PX);
      let newDur = resizingState.startDur;
      let newStart = resizingState.originalStartMins;

      if (resizingState.direction === "bottom") {
        let tentativeEnd =
          resizingState.originalStartMins + resizingState.startDur + deltaMins;
        if (tentativeEnd - resizingState.originalStartMins < 10)
          tentativeEnd = resizingState.originalStartMins + 10;
        newDur = tentativeEnd - resizingState.originalStartMins;
      } else {
        let tentativeStart = resizingState.originalStartMins + deltaMins;
        if (tentativeStart < resizingState.boundTop)
          tentativeStart = resizingState.boundTop;
        if (
          resizingState.originalStartMins +
            resizingState.startDur -
            tentativeStart <
          10
        )
          tentativeStart =
            resizingState.originalStartMins + resizingState.startDur - 10;
        newStart = tentativeStart;
        newDur =
          resizingState.originalStartMins + resizingState.startDur - newStart;
      }

      setItems(() => {
        const updatedSnapshot = {
          ...resizingState.originalItems,
          [resizingState.id]: {
            ...resizingState.originalItems[resizingState.id],
            dur: newDur,
            ...(resizingState.direction === "top"
              ? { startMins: newStart }
              : {}),
          },
        };
        // 해소 목록은 이 스냅샷에서 바로 뽑는다 — 렌더의 board 를 쓰면 매 프레임
        // 새 배열이라 이 effect 가 통째로 다시 걸리고, 리사이즈를 끝내는 click
        // 리스너의 50ms 지연이 움직일 때마다 초기화된다.
        const { newItems } = resolveOverlaps(
          updatedSnapshot,
          boardOf(updatedSnapshot),
          resizingState.id,
        );
        return newItems;
      });
    };

    const handleGlobalClick = () => {
      persistResize(resizingState); // fire-and-forget — 실패는 내부에서 reload 롤백
      setResizingState(null);
    };
    window.addEventListener("mousemove", handleMouseMove);
    const timer = setTimeout(
      () => window.addEventListener("click", handleGlobalClick),
      50,
    );
    return () => {
      clearTimeout(timer);
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("click", handleGlobalClick);
    };
  }, [resizingState, persistResize]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  const computeDropTarget = useCallback(
    (active) => {
      if (!active) return null;
      const rect =
        active.rect?.current?.translated || active.rect?.current?.initial;
      if (!rect) return null;

      const activeIdLocal = active.id;
      const centerX = rect.left + rect.width / 2;
      const centerY = rect.top + rect.height / 2;
      const topY = rect.top;

      const poolRect = poolDOMRef.current?.getBoundingClientRect();
      const tlRect = timelineDOMRef.current?.getBoundingClientRect();

      const isOverPool =
        !!poolRect &&
        centerX >= poolRect.left &&
        centerX <= poolRect.right &&
        centerY >= poolRect.top &&
        centerY <= poolRect.bottom;
      const isOverTimeline =
        !isOverPool &&
        !!tlRect &&
        centerX >= tlRect.left &&
        centerX <= tlRect.right &&
        centerY >= tlRect.top &&
        centerY <= tlRect.bottom;

      if (isOverPool) {
        let insertIndex = pool.filter((id) => id !== activeIdLocal).length;
        if (poolDOMRef.current) {
          const cardEls = Array.from(
            poolDOMRef.current.querySelectorAll("[data-pool-id]"),
          ).filter((el) => el.getAttribute("data-pool-id") !== activeIdLocal);
          let closestDist = Infinity,
            closestId = null,
            closestIsAfter = true,
            closestRect = null;

          cardEls.forEach((el) => {
            const r = el.getBoundingClientRect();
            const cx = r.left + r.width / 2;
            const cy = r.top + r.height / 2;
            const dist = Math.hypot(centerX - cx, centerY - cy);
            if (dist < closestDist) {
              closestDist = dist;
              closestId = el.getAttribute("data-pool-id");
              closestIsAfter =
                centerY > cy || (centerY >= r.top && centerX > cx);
              closestRect = r;
            }
          });
          if (closestId)
            insertIndex = closestIsAfter
              ? pool.filter((id) => id !== activeIdLocal).indexOf(closestId) + 1
              : pool.filter((id) => id !== activeIdLocal).indexOf(closestId);
          return {
            region: "pool",
            insertIndex,
            caretRect: closestRect,
            caretAfter: closestId ? undefined : undefined,
          };
        }
        return { region: "pool", insertIndex };
      }
      if (isOverTimeline) {
        // 검색 결과·챗봇 추천은 타임라인에 직접 놓을 수 없다 — 후보(POOL)에 먼저
        // 담는 흐름만 허용한다. 여기서 끊으면 드롭뿐 아니라 미리보기·하이라이트도 꺼진다.
        const from = active.data?.current?.from;
        if (from === "search" || from === "chatbot") return { region: null };

        const relativeY =
          topY - tlRect.top + (timelineDOMRef.current?.scrollTop || 0);
        // 픽셀은 축 기준이라 절대 오프셋으로 되돌린다 — timelineStart 는 여행 첫 Day 의 00:00
        const calcMins =
          timelineStart + Math.round((relativeY - TL_PAD_TOP) / PX);
        let dropMins = Math.round(calcMins / SNAP) * SNAP;
        const dur = items[activeIdLocal]?.dur || 60; // 기본 소요시간 60분
        // 시작은 여행 기간 안에 머문다 — 축이 전체 기간이라 Day 자정 벽은 없고,
        // 마지막 Day 의 24:00 만이 상한이다. 끝은 그 너머로 넘쳐도 막지 않는다 —
        // 절대 오프셋에선 넘친 꼬리가 그대로 다음 Day 위에 놓인다.
        //
        // 바닥은 "보고 있는 Day 의 자정을 넘어온 블록의 끝"이다(화면의 .tl-spill
        // 띠가 덮은 구간) — 그 위에 놓지 못하게 막아, 띠를 겹쳐 놓는 조작을
        // 미리 끊는다. 옮기는 블록 자신은 제외한다(제 꼬리에 제가 막히면 안 된다).
        // 이 하한은 띠와 한 몸이라 띠를 걷어낼 때 함께 없어진다 — 겹침 해소는
        // 이미 Day 경계를 모르고, 앞 블록의 끝(lastEnd)이 같은 일을 한다.
        const floorMins = spilloverFloorOf(items, dayBase, activeIdLocal);
        dropMins = Math.max(floorMins, Math.min(dropMins, timelineEnd - SNAP));
        return { region: "timeline", dropMins, dur };
      }

      // 후보 목록·타임라인 어느 쪽도 아니면 "보드 밖" — 놓으면 삭제다.
      // 별도 휴지통 영역 대신 이 판정을 쓴다(후보 목록이 그만큼 넓어진다).
      // 단 검색 결과는 아직 블록이 아니라 지울 대상이 없다 — 그냥 취소한다.
      if (active.data?.current?.from === "search") return { region: null };
      return { region: "discard" };
    },
    [pool, items, timelineStart, timelineEnd, dayBase],
  );

  // 렌더에서 쓰는 드래그 출처 정보({ from, place })는 state 로 둔다 —
  // ref(activeDragRef)는 스크롤 핸들러용이고 렌더 중에 읽으면 안 된다(react-hooks/refs).
  const [activeDragMeta, setActiveDragMeta] = useState(null);

  const handleDragStart = (event) => {
    if (resizingState) return;
    setActiveId(event.active.id);
    setActiveDragMeta(event.active.data?.current ?? null);
    activeDragRef.current = event.active;
    setDragPreview(computeDropTarget(event.active));
  };
  const handleDragMove = (event) => {
    activeDragRef.current = event.active;
    setDragPreview(computeDropTarget(event.active));
  };
  const handleDragCancel = () => {
    setActiveId(null);
    setActiveDragMeta(null);
    activeDragRef.current = null;
    setDragPreview(null);
  };

  const handleDragEnd = (event) => {
    const { active } = event;
    const activeIdLocal = active.id;
    const isFromPool = pool.includes(activeIdLocal);
    const isFromSearch = active.data.current?.from === "search";
    const isFromChatbot = active.data.current?.from === "chatbot";
    const target = computeDropTarget(active);

    setActiveId(null);
    setActiveDragMeta(null);
    activeDragRef.current = null;
    setDragPreview(null);

    if (!target || !target.region) return;

    // 💡 1. 외부 소스(카카오 검색·챗봇 추천)를 드래그해서 놓았을 때 — 드롭 = 블록
    // 생성(POST). 타임라인 직행은 computeDropTarget 이 region: null 로 끊는다 —
    // 느슨한 블록은 후보(POOL)에 먼저 담는 흐름만 허용한다.
    if (isFromSearch || isFromChatbot) {
      if (target.region !== "pool") return;

      let newId;
      let newBlock;
      if (isFromSearch) {
        const place = active.data.current.place;
        newId = `search-${place.placeId}-${Date.now()}`;

        // 검색 결과(백엔드 DTO)를 우리 앱의 블록 데이터 구조로 변환.
        // 좌표·placeId 를 버리면 장소성 블록의 서버 검증(BLOCK400)에 걸리고
        // 지도 핀도 찍을 수 없다.
        newBlock = {
          id: newId,
          cat: catFromKakaoGroup(place.categoryCode),
          sub: place.category || "검색된 장소",
          name: place.name,
          address: place.address,
          detail: place.phone || "",
          dur: 60, // 기본 소요시간 1시간
          startMins: null, // 후보(POOL) 블록은 시각 없는 느슨한 블록
          cost: 0,
          lat: place.lat,
          lng: place.lng,
          placeId: place.placeId,
          source: "KAKAO",
          auto: false,
        };
      } else {
        // 챗봇 추천(Candidate)은 서버 필드명 그대로 온다 — 어댑터 매핑으로 변환.
        // detail 에 축제 기간 등이 실려 오면 생성 직후 PATCH 로 함께 저장된다.
        const cand = active.data.current.candidate;
        newId = `search-bot-${Date.now()}`;
        newBlock = {
          id: newId,
          cat: blockApi.CAT_FROM_SERVER[cand.category] ?? "etc",
          sub: cand.subCategory || "",
          name: cand.name,
          address: cand.address || "",
          detail: cand.detail || "",
          dur: 60,
          startMins: null,
          cost: 0,
          lat: cand.lat ?? null,
          lng: cand.lng ?? null,
          placeId: cand.placeId != null ? String(cand.placeId) : null,
          source: cand.source ?? "BOT",
          auto: false,
        };
      }

      // 명세 MAP-03: 중복 등록 자체는 정상 시나리오라 막지 않는다 — 같은 카페를
      // 다른 날 재방문하거나 같은 환승역을 왕복으로 지난다. 담는 순간 "이미 있다"만
      // 알려주고 생성은 그대로 한다.
      //
      // 숙소는 뺀다. 3박이면 숙소 블록이 당연히 여러 개고, 하루도 숙소에서 열어
      // 숙소로 닫는다 — 알릴수록 방해만 된다.
      const isDuplicatePlace =
        newBlock.placeId != null &&
        newBlock.cat !== "stay" &&
        Object.values(items).some((b) => b.placeId === newBlock.placeId);
      if (isDuplicatePlace) showToast("이미 담은 장소예요");

      const insertAt = Math.max(0, Math.min(target.insertIndex, pool.length));
      const nextPool = [...pool];
      nextPool.splice(insertAt, 0, newId);

      // 낙관 적용
      setItems((prev) => ({ ...prev, [newId]: newBlock }));
      setPool(nextPool);

      (async () => {
        try {
          const [before, after] = neighborKeysAround(nextPool, insertAt, items);
          const orderKey = safeKeyBetween(before, after);
          const created = await blockApi.createBlock(projectId, {
            ...newBlock,
            startMins: null, // 후보(POOL)로 생성된다
            orderKey,
          });
          // 전화번호(detail)는 생성 바디에 없다(명세) — 생성 직후 별도 저장
          if (newBlock.detail) {
            await blockApi.updateBlockFields(created.blockId, {
              detail: newBlock.detail,
            });
          }
          adoptServerId(newId, created.blockId, { orderKey });
        } catch (e) {
          rollbackToServer(e);
        }
      })();
      return;
    }

    // 기존 풀/타임라인 내의 이동 처리 로직 유지
    if (target.region === "discard") {
      // 보드 밖에 놓았다 = 삭제. async 삭제(서버 왕복 포함)는 별도 함수로 —
      // 드래그 핸들러는 동기로 끝낸다
      handleDeleteBlock(activeIdLocal);
      return;
    }

    if (target.region === "pool") {
      // 재정렬(pool→pool)과 내리기(chain→pool)를 최종 배열 하나로 통일해 계산한다
      const withoutActive = pool.filter((id) => id !== activeIdLocal);
      const insertAt = Math.max(
        0,
        Math.min(target.insertIndex, withoutActive.length),
      );
      const nextPool = [...withoutActive];
      nextPool.splice(insertAt, 0, activeIdLocal);

      // 제자리 드롭(집었다 그대로 놓음)이면 아무것도 하지 않는다
      const unchanged =
        isFromPool &&
        nextPool.length === pool.length &&
        nextPool.every((id, i) => id === pool[i]);
      if (unchanged) return;

      // 낙관 적용 — 후보의 자리는 "오프셋 없음"이다. 보드에서 내리는 일이 곧
      // 오프셋을 지우는 일이라, 시각을 남겨 두면 그 블록이 후보 목록과 시간축에
      // 동시에 있게 된다(pool→pool 이동이면 이미 null 이라 no-op).
      setItems((prev) =>
        prev[activeIdLocal]?.startMins == null
          ? prev
          : {
              ...prev,
              [activeIdLocal]: { ...prev[activeIdLocal], startMins: null },
            },
      );
      setPool(nextPool);

      // 서버 저장: 후보로 이동/재정렬 = startOffsetMinutes null + 이웃 사이 orderKey.
      // 후보는 시간축 위에 없다 — 체인에 다시 올라갈 때 드롭 위치로 다시 정해진다.
      if (isServerBlock(activeIdLocal)) {
        (async () => {
          try {
            const [before, after] = neighborKeysAround(nextPool, insertAt, items);
            const orderKey = safeKeyBetween(before, after);
            await blockApi.moveBlock(activeIdLocal, {
              startOffsetMinutes: null,
              orderKey,
            });
            // 다음 이동의 이웃 계산이 정확하도록 로컬에도 새 키를 반영
            setItems((prev) =>
              prev[activeIdLocal]
                ? {
                    ...prev,
                    [activeIdLocal]: { ...prev[activeIdLocal], orderKey },
                  }
                : prev,
            );
          } catch (e) {
            rollbackToServer(e); // 키 생성 실패(키 중복 등)·요청 실패 공통 안전망
          }
        })();
      }
      return;
    }

    if (target.region === "timeline") {
      const dropMins = target.dropMins;

      // 계산을 updater 밖에서 한다 — 저장(async)이 결과를 필요로 하고,
      // updater 안의 중첩 setState(순수성 위반)도 함께 없어진다. 수학은 동일.
      const updatedItems = {
        ...items,
        [activeIdLocal]: { ...items[activeIdLocal], startMins: dropMins },
      };
      // 해소 목록은 놓은 뒤의 보드다 — 옮긴 블록은 오프셋을 얻는 순간 저절로
      // 들어온다(후보에서 올라온 경우 포함). 활성 탭이 아니라 놓인 자리가 기준이라,
      // 다른 Day 의 카드 위에 놓아도 그 Day 의 이웃이 제대로 밀린다.
      const { newItems, newChain } = resolveOverlaps(
        updatedItems,
        boardOf(updatedItems),
        activeIdLocal,
      );

      // 낙관 적용 — 밀린 이웃이 24:00 을 넘겨도 그대로 둔다.
      // 절대 오프셋에선 1440 을 넘긴 자리가 곧 다음 Day 다.
      setItems(newItems);
      if (isFromPool)
        setPool((prev) => prev.filter((id) => id !== activeIdLocal));

      // 서버 저장: 옮긴 블록 1건의 position(시작 오프셋·orderKey) +
      // 겹침 해소로 밀린 이웃들의 위치. resolveOverlaps 는 이동 블록 외의
      // 상대 순서를 보존하므로 position 은 정확히 1건이다(명세와 일치).
      if (isServerBlock(activeIdLocal)) {
        (async () => {
          try {
            const [before, after] = neighborKeysAround(
              newChain,
              newChain.indexOf(activeIdLocal),
              newItems,
            );
            const orderKey = safeKeyBetween(before, after);

            await blockApi.moveBlock(activeIdLocal, {
              startOffsetMinutes: newItems[activeIdLocal].startMins,
              orderKey,
            });

            // 다음 이동의 이웃 계산이 정확하도록 로컬에도 새 위치 값을 반영
            setItems((prev) =>
              prev[activeIdLocal]
                ? {
                    ...prev,
                    [activeIdLocal]: { ...prev[activeIdLocal], orderKey },
                  }
                : prev,
            );

            // 자리만 밀린 나머지 이웃들
            await persistMovedOffsets(
              newChain,
              items,
              newItems,
              activeIdLocal,
            );
          } catch (e) {
            rollbackToServer(e);
          }
        })();
      }
    }
  };

  // 눈금은 여행 전체를 30분 간격으로 덮는다 — 하루가 49개, 3일이면 145개다.
  // (긴 여행에서 보이는 범위만 그리는 것은 아직 하지 않았다.)
  // 새벽 빈 공간은 아래 최초 스크롤·탭 점프가 첫 블록 위치로 건너뛴다.
  const timeSlots = [];
  for (let t = timelineStart; t <= timelineEnd; t += 30) timeSlots.push(t);

  // ── 활성 Day 파생 = 뷰포트를 가장 많이 차지하는 Day ─────────
  /**
   * 뷰포트가 덮는 분 구간을 각 Day 의 [i*1440, (i+1)*1440) 과 교차시켜, 겹침이
   * 가장 넓은 Day 키를 준다. 경계에 걸쳐 있으면 화면을 더 많이 차지한 쪽이 이긴다.
   * 분 → px 환산은 보드 어디서나 (분 - timelineStart) * PX + TL_PAD_TOP 이다
   * (computeDropTarget 의 역산과 같은 식이라 여백 항까지 맞춰 둔다).
   */
  const dominantDayOf = (scrollTop, viewportH) => {
    const from = (scrollTop - TL_PAD_TOP) / PX + timelineStart;
    const to = (scrollTop + viewportH - TL_PAD_TOP) / PX + timelineStart;
    let best = dayKeys[0];
    let bestOverlap = -1;
    dayKeys.forEach((day, i) => {
      const dayFrom = i * blockApi.MINUTES_PER_DAY;
      const overlap =
        Math.min(to, dayFrom + blockApi.MINUTES_PER_DAY) -
        Math.max(from, dayFrom);
      if (overlap > bestOverlap) {
        bestOverlap = overlap;
        best = day;
      }
    });
    return best;
  };

  // 탭 점프가 도는 동안엔 클릭이 이긴다 — 부드러운 스크롤이 지나가는 중간 Day 들이
  // 하이라이트를 흔들고 presence 를 헛되이 쏘는 것을 막는다. 목표 Day 에 닿으면
  // (=스크롤이 거기 도착했다) 그 자리에서 풀고, 사용자가 도중에 직접 스크롤을
  // 가로채 목표에 영영 안 닿는 경우는 타이머가 풀어 준다.
  const jumpLockRef = useRef(null); // 점프 목표 dayKey
  const jumpTimerRef = useRef(0);
  const dominantRafRef = useRef(0);
  const releaseJumpLock = () => {
    jumpLockRef.current = null;
    clearTimeout(jumpTimerRef.current);
  };
  const syncDominantDay = () => {
    const el = timelineDOMRef.current;
    if (!el) return;
    const next = dominantDayOf(el.scrollTop, el.clientHeight);
    if (jumpLockRef.current) {
      if (next !== jumpLockRef.current) return;
      releaseJumpLock();
    }
    setScrolledDay((prev) => (prev === next ? prev : next));
  };
  // 스크롤 이벤트마다 재지 않는다 — 같은 핸들러가 드래그 중 computeDropTarget 까지
  // 부르므로, Day 계산은 프레임당 한 번으로 묶는다.
  const scheduleDominantDay = () => {
    if (dominantRafRef.current) return;
    dominantRafRef.current = requestAnimationFrame(() => {
      dominantRafRef.current = 0;
      syncDominantDay();
    });
  };

  /**
   * 그 Day 의 첫 블록으로 스크롤한다. 배치된 블록이 없으면 그 Day 의 00:00 으로.
   * Day 별 스크롤 위치 기억은 없앴다 — 스크롤 컨테이너가 하나라 위치도 하나다.
   */
  const jumpToDay = (dayKey, behavior = "smooth") => {
    const el = timelineDOMRef.current;
    if (!el) return;
    const base = (dayNoOf(dayKey) - 1) * blockApi.MINUTES_PER_DAY;
    const target = firstStartOf(board, items, dayKey) ?? base;
    // 15분(=30px)만 앞에서 멈춘다 — 목표가 sticky Day 머리글에 가리지 않게
    const top = Math.max(0, (target - timelineStart - 15) * PX);
    setScrolledDay(dayKey);
    // 이미 그 자리면 스크롤 이벤트가 안 난다 — 잠그지 않고, 앞선 점프의 락이
    // 남아 있으면 여기서 푼다(위치가 이미 목표라 방금 넣은 값이 곧 정답이다)
    if (Math.abs(el.scrollTop - top) < 1) {
      releaseJumpLock();
      return;
    }
    clearTimeout(jumpTimerRef.current);
    jumpLockRef.current = dayKey;
    // 시한이 다하면 락을 풀고 **그 자리에서 다시 잰다.** 풀기만 하면 안 된다 —
    // 락이 걸린 동안의 스크롤 이벤트는 syncDominantDay 의 조기 반환에 전부
    // 삼켜지므로, 사용자가 휠로 점프를 가로채고 멈춰 서면(스무스 스크롤은
    // 취소된다) 락이 풀린 뒤에도 이벤트가 더는 오지 않는다. 그러면 하이라이트가
    // 목표 Day 에 얼어붙고 presence·커서 dayNo·지도까지 그 Day 를 계속 방송해
    // 팀원에게 내가 있지도 않은 Day 를 보고 있다고 거짓말한다. 최대 스크롤
    // 위치라 scrollTo 가 조용한 no-op 이 되는 경우도 같은 길로 구제된다.
    jumpTimerRef.current = setTimeout(() => {
      if (jumpLockRef.current !== dayKey) return;
      jumpLockRef.current = null;
      syncDominantDay();
    }, 1200);
    el.scrollTo({ top, behavior });
  };

  // 보드를 열면 첫 Day 의 첫 블록이 보이게 한 번만 맞춘다(부드럽게 갈 이유가 없어
  // 즉시). 그 뒤로 스크롤 위치를 건드리는 것은 탭 점프뿐이다 — board/items 가
  // 바뀔 때마다 스크롤을 뺏으면 편집 중에 화면이 튄다.
  const didInitialScrollRef = useRef(false);
  useEffect(() => {
    if (status !== "loaded" || didInitialScrollRef.current) return;
    const el = timelineDOMRef.current;
    if (!el) return;
    didInitialScrollRef.current = true;
    const firstDay = dayKeys[0];
    const base = (dayNoOf(firstDay) - 1) * blockApi.MINUTES_PER_DAY;
    const target = firstStartOf(board, items, firstDay) ?? base;
    el.scrollTop = Math.max(0, (target - timelineStart - 15) * PX);
  }, [status, dayKeys, board, items, timelineStart]);

  // ── 라이브 커서 송신 (7단계) — 명세의 50ms 스로틀, 대시보드 전역 ──
  // 타임라인 위에서는 "가로 비율 + 절대 분 오프셋"(area:"tl") — 상대와 내 스크롤·시작
  // 시각이 달라도 같은 시간 위치에 그려진다(축 기준선 timelineStart 를 더해 절대 분으로 만든다). 그 밖(후보·사이드 등)에서는 페이지
  // 비율 좌표(area:"page") — 창 크기가 달라도 대략 같은 자리를 가리킨다.
  const lastCursorSendRef = useRef(0);
  const handlePageCursorMove = (e) => {
    const now = Date.now();
    if (now - lastCursorSendRef.current < 50) return;
    lastCursorSendRef.current = now;
    const dayNo = dayNoOf(activeDay);

    const tlEl = timelineDOMRef.current;
    const tlRect = tlEl?.getBoundingClientRect();
    const inTimeline =
      !!tlRect &&
      e.clientX >= tlRect.left &&
      e.clientX <= tlRect.right &&
      e.clientY >= tlRect.top &&
      e.clientY <= tlRect.bottom;

    if (inTimeline) {
      sendCursor({
        area: "tl",
        x: (e.clientX - tlRect.left - TL_PAD_LEFT) / (tlRect.width - TL_PAD_LEFT),
        y:
          timelineStart +
          (e.clientY - tlRect.top + tlEl.scrollTop - TL_PAD_TOP) / PX,
        dayNo,
      });
      return;
    }

    const pageRect = pageDOMRef.current?.getBoundingClientRect();
    if (!pageRect) return;
    sendCursor({
      area: "page",
      x: (e.clientX - pageRect.left) / pageRect.width,
      y: (e.clientY - pageRect.top) / pageRect.height,
      dayNo,
    });
  };

  // 💡 드래그 중인 임시 아이템 정의 (검색 패널에서 드래그할 경우 임시 객체를 만들어 보여줌)
  let draggedItem = null;
  if (activeId) {
    if (activeDragMeta?.from === "search") {
      const place = activeDragMeta.place;
      draggedItem = {
        id: activeId,
        cat: catFromKakaoGroup(place.categoryCode),
        name: place.name,
        sub: place.category,
        address: place.address,
        dur: 60,
        cost: 0,
      };
    } else if (activeDragMeta?.from === "chatbot") {
      const cand = activeDragMeta.candidate;
      draggedItem = {
        id: activeId,
        cat: blockApi.CAT_FROM_SERVER[cand.category] ?? "etc",
        name: cand.name,
        sub: cand.subCategory,
        address: cand.address,
        dur: 60,
        cost: 0,
      };
    } else {
      draggedItem = items[activeId];
    }
  }

  const isDraggingFromPool = activeId ? pool.includes(activeId) : false;
  // 외부 소스(검색·챗봇) 드래그는 풀 카드 모양의 오버레이로 그린다
  const isDraggingFromSearch =
    activeDragMeta?.from === "search" || activeDragMeta?.from === "chatbot";

  let displayItems = items;

  if (dragPreview?.region === "timeline" && activeId && draggedItem) {
    const draggedDur = dragPreview.dur || draggedItem.dur || 60;
    const tempItems = {
      ...items,
      [activeId]: {
        ...draggedItem,
        startMins: dragPreview.dropMins,
        dur: draggedDur,
      },
    };
    // 미리보기도 확정(handleDragEnd)과 같은 계산이어야 고스트가 실제로 놓일 자리를
    // 가리킨다 — 목록은 놓았다고 가정한 보드 전체다.
    // 겹침 해소가 돌려주는 새 순서는 더 쓰지 않는다 — 화면 순서는 이제 오프셋
    // 정렬이고, 밀려난 블록의 새 시각은 newItems 에 들어 있다.
    const { newItems } = resolveOverlaps(
      tempItems,
      boardOf(tempItems),
      activeId,
    );
    displayItems = newItems;
  }

  // 축이 여행 전체 한 줄이라 그릴 것도 활성 Day 가 아니라 **보드 전체**다.
  // 목록은 드래그 미리보기까지 반영된 displayItems 에서 뽑는다 — 밀려난 블록의
  // 새 시각이 거기 들어 있고, 소속 판정(오프셋 유무)도 같은 스냅샷을 봐야
  // 미리보기와 확정이 갈라지지 않는다.
  // 정렬이 곧 이 목록의 순서다 — "다음 항목"이 이동 버튼(🚗) 위치·간격과
  // boundTop 의 기준이라 시간이 곧 순서여야 한다.
  const boardItems = boardOf(displayItems)
    .filter((id) => id !== activeId)
    .map((id) => {
      const item = displayItems[id];
      if (!item) return null;
      return {
        id,
        item,
        startMins: item.startMins,
        endMins: item.startMins + item.dur,
      };
    })
    .filter(Boolean)
    .sort((a, b) => a.startMins - b.startMins);

  // 자정을 넘어 이 Day 로 이어지는 블록들의 읽기 전용 띠. Day 하나만 그리던
  // 시절, 앞 Day 의 체인에 있는 그 한 행이 카드 목록 밖이라 Day 2 의 새벽이
  // 텅 빈 것처럼 보이는 것을 메우던 장치다.
  // 보드 전체를 그리는 지금은 그 블록이 boardItems 안에 카드로도 들어 있어
  // 활성 Day 에서 카드와 띠가 겹쳐 그려진다 — 띠를 걷어내는 것은 뒤 태스크 몫이라
  // 여기서는 그대로 둔다.
  const spilloverBands = spilloversInto(displayItems, dayBase, activeId);

  // 편집 배지에 쓸 이름 — 락 소유자가 멤버 목록에 없으면(탈퇴 직후 등) 뭉뚱그린다
  const nicknameOf = (memberId) =>
    boardMembers.find((m) => m.memberId === memberId)?.nickname ?? "다른 멤버";
  // 남이 잡은 락만 배지가 된다 — 내 락(내 다른 탭 포함, memberId 기준)은 표시하지 않는다
  const lockBadgeOf = (blockId) => {
    const holder = detailLocks[blockId];
    return holder != null && holder !== currentUser?.id
      ? nicknameOf(holder)
      : null;
  };

  // 블록 좌상단의 "가장 최근 수정자" 아바타. 우선순위는
  //   ① 시드 이후 도착한 op(lastEditors) → ② 서버 영속값(lastEditedById, PRS-04)
  //   → ③ 작성자(authorId, 005 마이그레이션 이전 행의 폴백)
  // ①이 ②보다 앞서는 건 op 가 스냅샷보다 뒤의 사실이기 때문이다(시드 때 ①은 비워진다).
  // 멤버 정보가 없으면(탈퇴 등) 감춘다.
  const editorBadgeOf = (blockId) => {
    const memberId =
      lastEditors[blockId] ??
      items[blockId]?.lastEditedById ??
      items[blockId]?.authorId;
    if (memberId == null) return null;
    const member = boardMembers.find((m) => m.memberId === memberId);
    if (!member) return null;
    return {
      id: memberId,
      name: member.nickname,
      profileImg: member.profileImg ?? null,
    };
  };

  // Day 탭에 찍을 "이 Day 를 보는 중" 멤버들 (커서 하트비트 기반).
  // 프로필 이미지까지 실어 탭에 아바타로 띄운다 — 테두리는 커서와 같은 멤버 색.
  const dayViewersOf = (dayKey) => {
    const dayNo = dayNoOf(dayKey);
    return Object.entries(viewingDays)
      .filter(([, d]) => d === dayNo)
      .map(([id]) => {
        const memberId = Number(id);
        const member = boardMembers.find((m) => m.memberId === memberId);
        return {
          id: memberId,
          name: member?.nickname ?? "다른 멤버",
          profileImg: member?.profileImg ?? null,
        };
      });
  };

  // 스냅샷이 시드되기 전(로딩 중)에는 보드를 그리지 않는다.
  // 에러일 때는 위 effect 가 그룹 페이지로 되돌린다.
  if (status !== "loaded") return null;

  // 💡 타임라인 드래그 미리보기 합치기
  if (dragPreview?.region === "timeline" && activeId && draggedItem) {
    boardItems.push({
      id: activeId,
      item: displayItems[activeId],
      startMins: displayItems[activeId].startMins,
      endMins: displayItems[activeId].startMins + displayItems[activeId].dur,
    });
    boardItems.sort((a, b) => a.startMins - b.startMins);
  }

  // 자정을 넘긴 블록이 마지막 눈금 밖으로도 그려져야 한다 — 컨테이너를 그만큼 늘린다.
  // endMins 가 유한하지 않은 항목(startMins 가 없는 후보 등)은 높이를 NaN 으로 만들므로 건너뛴다.
  const contentEnd = boardItems.reduce(
    (acc, it) => (Number.isFinite(it.endMins) ? Math.max(acc, it.endMins) : acc),
    timelineEnd,
  );

  return (
    <>
      {/* 개인 페이지 › 그룹명 › 프로젝트명. 그룹·프로젝트를 못 불러온 동안에는
          자리만 지키는 문구를 쓴다(빈 칸이 생기면 경로가 끊겨 보인다). */}
      <AppBar
        // 마지막 항목(프로젝트명)은 extra 의 제목이 대신한다 — 두 번 보이지 않게
        crumbs={[
          { label: "개인 페이지", to: "/my" },
          { label: group?.name ?? "그룹", to: `/groups/${groupId}` },
        ]}
        // 제목·기간·모드 전환을 상단바에 올린다 — 보드 위 툴바 띠를 없애
        // 세로 공간을 카드에 돌려주기 위함
        extra={
          <div className="dash-headbar">
            <span className="crumb-sep">›</span>
            <h1 className="dash-headbar-title">
              {project?.name ?? "여행 대시보드"}
            </h1>
            {/* 예전엔 그룹 페이지로 돌아가야만 수정할 수 있었다 — 보고 있는
                화면에서 바로 열 수 있게 제목 옆에 둔다.
                모바일은 읽기 전용이라 뺀다 — 보드는 못 고치는데 프로젝트 설정만
                고칠 수 있으면 앞뒤가 안 맞고, 제목에 쓸 폭도 그만큼 넓어진다. */}
            {project && !isMobile && (
              <button
                type="button"
                className="dash-headbar-edit"
                title="프로젝트 수정"
                aria-label="프로젝트 수정"
                onClick={() => setEditProjectOpen(true)}
              >
                ✎
              </button>
            )}
            {/* 모바일은 읽기 전용이라 고를 게 없다 — 토글 자체를 걷는다.
                누를 수 없는 버튼을 흐리게 남겨 두면 "왜 안 눌리지"가 된다. */}
            {!isMobile && (
              <div className="mode-switch">
                <button
                  className={`mode-tab ${viewMode === "edit" ? "on" : ""}`}
                  onClick={() => setViewMode("edit")}
                >
                  ✎ 편집
                </button>
                <button
                  className={`mode-tab ${viewMode === "read" ? "on" : ""}`}
                  onClick={() => setViewMode("read")}
                >
                  ≡ 읽기
                </button>
              </div>
            )}
          </div>
        }
        // 프로젝트 멤버(스냅샷 시드 + MEMBER_JOINED/LEFT 갱신)와 실시간 접속
        // 상태(PRESENCE) — 초록 점이 진짜 "지금 보는 중"을 뜻하게 됐다(6단계)
        members={boardMembers}
        activeMemberIds={Array.from(onlineIds)}
      />

      {/* 제목·기간·모드 전환은 상단바(AppBar extra)로 올라갔다 — 여기는 보드만.
          툴바 띠가 차지하던 세로 공간만큼 카드들이 위로 올라온다. */}
      <div className="dash-shell">
        {viewMode === "edit" ? (
          // 💡 사이드바까지 드래그 기능이 통하도록 DndContext를 가장 상위 껍데기로 이동시켰습니다!
          <DndContext
            sensors={sensors}
            autoScroll={dragPreview?.region === "timeline"}
            onDragStart={handleDragStart}
            onDragMove={handleDragMove}
            onDragEnd={handleDragEnd}
            onDragCancel={handleDragCancel}
          >
            <div
              className="dashboard-page dash-body"
              ref={pageDOMRef}
              onMouseMove={handlePageCursorMove}
              // 대시보드를 벗어나면(상단바 등) 상대 화면의 내 커서를 걷는다.
              // Day 탭 점은 유지된다 — 아직 이 Day 를 보는 중이므로.
              onMouseLeave={() =>
                sendCursor({ area: "leave", dayNo: dayNoOf(activeDay) })
              }
            >
              <div className="daycol">
                {dayKeys.map((day, i) => (
                  <DayTab
                    key={day}
                    label={`Day ${i + 1}`}
                    date={dayDate(project, i, "short")}
                    count={blocksOfDay(board, items, day).length}
                    // 탭은 이제 화면을 갈아끼우지 않는다 — 그 Day 로 스크롤할 뿐이고,
                    // 하이라이트도 스크롤이 정한 Day(activeDay)를 따라간다.
                    isActive={activeDay === day}
                    onClick={() => jumpToDay(day)}
                    viewers={dayViewersOf(day)}
                  />
                ))}
              </div>

              <div className="main">
                <div className="board plan-board">
                  <div className="bd-head">
                    {/* Day 번호·날짜는 축 위의 sticky 헤더(.tl-day-head)가 쥔다 —
                        축이 여행 전체 한 줄이라 여기 고정된 Day 표시를 같이 두면
                        스크롤을 따라 움직이는 표시와 어느 쪽이 진짜인지 어긋난다.
                        (날짜는 표시 전용이다. 여행 기간은 상단바 제목 옆 ✎ 에서
                        바꾼다 — 안내 문구가 그 자리를 알려 준다.) */}
                    <span
                      // tip-down: 헤더 바로 아래라 위로 열면 상단바에 가린다
                      className="hint-ico tip-down"
                      tabIndex={0}
                      aria-label="계획표 사용 안내"
                      data-tip="후보 블록을 원하는 시간에 끌어다 놓아 일정을 만들어요. 블록의 위·아래 가장자리를 누르면 10분 단위로 길이를 조절하고, 블록 사이 🚗 버튼으로 이동수단을 추가할 수 있어요. 여행 날짜는 위 제목 옆 ✎ 에서 바꿀 수 있어요."
                    >
                      ⓘ
                    </span>
                    <div className="right">
                      <button
                        className="auto-transport-btn"
                        onClick={() => regenerateAutoTransport(activeDay)}
                        disabled={
                          isGeneratingTransport ||
                          blocksOfDay(board, items, activeDay).filter(
                            (id) => !items[id]?.auto,
                          ).length < 2
                        }
                      >
                        {isGeneratingTransport
                          ? "생성 중..."
                          : "🚗 이동수단 자동 생성"}
                      </button>
                    </div>
                  </div>

                  <div
                    className={`tl ${dragPreview?.region === "timeline" ? "dropover" : ""}`}
                    ref={setTimelineRefs}
                    onScroll={() => {
                      if (activeDragRef.current)
                        setDragPreview(computeDropTarget(activeDragRef.current));
                      // 활성 Day 는 여기서 나온다(프레임당 1회로 묶여 있다)
                      scheduleDominantDay();
                    }}
                    // 그릴 길이(분 × PX)만 인라인으로 넘긴다 — 나머지 모양은 CSS(.tl)
                    style={{
                      height: `${(contentEnd - timelineStart) * PX + 120}px`,
                    }}
                  >
                    {/* 눈금·안내선 (--tl-pad-top/left 로 여백만 넘기고 색은 CSS) */}
                    <div
                      className="tl-bg"
                      style={{
                        "--tl-pad-top": `${TL_PAD_TOP}px`,
                        "--tl-pad-left": `${TL_PAD_LEFT}px`,
                      }}
                    >
                      {timeSlots.map((t) => (
                        <div
                          key={t}
                          className="tl-mark"
                          style={{ top: `${(t - timelineStart) * PX}px` }}
                        >
                          {/* Day 경계 눈금은 값이 다음 Day 의 00:00 과 같아 fmtTime 이
                              "00:00" 을 준다 — 하루의 끝은 24:00 으로 읽혀야 한다.
                              맨 위(t === 0)만 예외다. 여행의 시작이지 어느 하루의
                              끝이 아니라 00:00 그대로 둔다.
                              경계가 있는 건 눈금뿐이라 fmtTime 이 아니라 여기서 다룬다. */}
                          <span className="tl-mark-time">
                            {t > 0 && t % blockApi.MINUTES_PER_DAY === 0
                              ? "24:00"
                              : fmtTime(t)}
                          </span>
                          <div className="tl-mark-line" />
                        </div>
                      ))}
                      {/* 앞 Day 에서 자정을 넘어온 블록 — 카드가 아니라 띠다.
                          이 Day 의 00:00 에서 그 블록이 끝나는 시각까지 덮는다.
                          클릭·드래그·리사이즈 모두 없다(.tl-bg 가 pointer-events:none) —
                          실물은 앞 Day 에 있고 여기 있는 건 그 그림자일 뿐이다.
                          하루를 통째로 덮는 블록(24시간 초과)이면 자정에서 잘라
                          이 Day 를 가득 채우고, 라벨이 "온종일 이어짐"으로 바뀐다.
                          띠는 Day 창의 물건이라 축(여행 전체)이 아니라 dayBase~dayEnd
                          로 잡는다 — 그래서 top 을 CSS 의 0 대신 여기서 직접 준다. */}
                      {spilloverBands.map((band) => (
                        <div
                          key={`spill-${band.id}`}
                          className="tl-spill"
                          style={{
                            "--dc": catOf(band.item).hex,
                            top: `${(dayBase - timelineStart) * PX}px`,
                            height: `${(Math.min(band.endMins, dayEnd) - dayBase) * PX}px`,
                          }}
                        >
                          <span className="tl-spill-label">
                            <span className="tl-spill-name">
                              {band.item.name}
                            </span>
                            <span className="tl-spill-time">
                              {band.endMins >= dayEnd
                                ? `Day ${blockApi.dayNoOfOffset(band.item.startMins)} 에서 이어짐 · 온종일`
                                : `Day ${blockApi.dayNoOfOffset(band.item.startMins)} 에서 이어짐 · ${fmtTime(band.endMins)} 까지`}
                            </span>
                          </span>
                        </div>
                      ))}
                      {dragPreview?.region === "timeline" && draggedItem && (
                        <div
                          className="tl-ghost"
                          style={{
                            "--dc": catOf(draggedItem).hex,
                            "--cb": catOf(draggedItem).bg,
                            top: `${(dragPreview.dropMins - timelineStart) * PX}px`,
                            height: `${(dragPreview.dur || draggedItem.dur || 30) * PX}px`,
                          }}
                        >
                          <span className="tl-ghost-label">
                            {fmtTime(dragPreview.dropMins)} 에 놓기
                          </span>
                        </div>
                      )}
                    </div>

                    <div
                      className="tl-slots"
                      style={{
                        "--tl-pad-top": `${TL_PAD_TOP}px`,
                        "--tl-pad-left": `${TL_PAD_LEFT}px`,
                      }}
                    >
                      {boardItems.map((data, index) => {
                        // 위 모서리를 끌어올릴 수 있는 한계 — 앞 카드의 끝이다.
                        // 목록이 보드 전체라 자정을 넘어온 블록도 그냥 앞 카드로
                        // 여기 들어 있다(예전엔 Day 로 걸러 목록 밖이라 띠의
                        // 바닥값을 따로 구해야 했다). 맨 앞 카드는 여행 전체에서
                        // 가장 이른 블록이라 축의 시작 말고는 막을 것이 없다.
                        // 이 값이 리사이즈 핸들러의 유일한 하한이라(resizingState.
                        // boundTop) 여기만 올리면 미리보기와 확정이 갈라지지 않는다.
                        const boundTop =
                          index > 0
                            ? boardItems[index - 1].endMins
                            : timelineStart;

                        const nextData = boardItems[index + 1];
                        const showGapBtn =
                          nextData &&
                          data.item.cat !== "trans" &&
                          nextData.item.cat !== "trans";

                        // 💡 추가된 부분: 두 일정 사이의 빈 시간(gap) 계산
                        const gapMins = nextData
                          ? nextData.startMins - data.endMins
                          : 0;
                        const hasEnoughGap = gapMins >= 15; // 빈 시간이 15분 이상인지 확인

                        const isThisActiveTimelineCard =
                          activeId === data.id &&
                          dragPreview?.region === "timeline";

                        return (
                          <React.Fragment key={data.id}>
                            {isThisActiveTimelineCard ? (
                              // 드래그 중인 카드의 원래 자리 — 자리만 잡고 보이지 않게
                              <div className="slot-ghost">
                                <TimelineCard
                                  id={data.id}
                                  item={data.item}
                                  startMins={data.startMins}
                                  endMins={data.endMins}
                                  timelineStart={timelineStart}
                                />
                              </div>
                            ) : (
                              <TimelineCard
                                id={data.id}
                                item={data.item}
                                startMins={data.startMins}
                                endMins={data.endMins}
                                resizingState={resizingState}
                                onResizeStart={handleResizeStart}
                                timelineStart={timelineStart}
                                boundTop={boundTop}
                                onEditBlock={openBlockDetail}
                                lockedBy={lockBadgeOf(data.id)}
                                editor={editorBadgeOf(data.id)}
                              />
                            )}

                            {/* 💡 업데이트된 부분: 블록과 겹치지 않는 스마트 교통 아이콘.
                                hover 색 반전은 CSS(.trans-chip:hover)가 한다 — 예전에는
                                onMouseEnter 에서 스타일을 직접 바꿨다. */}
                            {showGapBtn && !isThisActiveTimelineCard && (
                              <div
                                className={`trans-slot ${hasEnoughGap ? "has-gap" : ""}`}
                                style={{
                                  // 시간이 비어있으면 갭의 정중앙에, 딱 붙어있으면 경계선에
                                  top: hasEnoughGap
                                    ? `${(data.endMins + gapMins / 2 - timelineStart) * PX}px`
                                    : `${(data.endMins - timelineStart) * PX}px`,
                                }}
                              >
                                <button
                                  type="button"
                                  className="trans-chip"
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    // 버튼이 보드 전체에 깔리므로 Day 는 활성 탭이
                                    // 아니라 앞 블록이 실제로 앉은 Day 다 —
                                    // 활성 Day 를 넘기면 다른 Day 의 버튼이
                                    // "체인에 없는 블록"으로 조용히 튕긴다.
                                    handleAddSingleTransport(
                                      `d${blockApi.dayNoOfOffset(data.startMins)}`,
                                      data.id,
                                      nextData.id,
                                    );
                                  }}
                                >
                                  <span className="trans-chip-ico">🚗</span>
                                  <span className="trans-chip-label">
                                    {hasEnoughGap
                                      ? "이동 시간 계산"
                                      : "이동 추가"}
                                  </span>
                                </button>
                              </div>
                            )}
                          </React.Fragment>
                        );
                      })}

                      {boardItems.length === 0 && (
                        <div className="endzone">
                          ＋ 비어있는 타임라인의 원하는 시간 위치로 드래그하여
                          일정을 추가하세요
                        </div>
                      )}
                    </div>

                    {/* Day 구분 헤더 — 하루가 시작하는 자리(dayIndex × 24시간)마다
                        하나씩, 그 Day 의 구간 안에서 sticky 로 따라온다.
                        Day 를 카드의 컨테이너로 만들지 않는다 — 만들면 자정을 넘는
                        블록을 두 칸에 걸쳐 쪼개야 하고, 그게 이번에 없앤 두-행
                        모델이다. 카드는 .tl-slots 한 곳에 그대로 있고 여기 있는
                        것은 라벨뿐이다.
                        반투명이라 카드가 헤더 밑을 지나가는 게 그대로 보인다 —
                        여행이 챕터로 끊기는 게 아니라 이어진다는 표시다.
                        pointer-events 는 CSS 에서 끈다(카드 위에 뜨므로). */}
                    <div
                      className="tl-days"
                      style={{
                        "--tl-pad-top": `${TL_PAD_TOP}px`,
                        "--tl-pad-left": `${TL_PAD_LEFT}px`,
                      }}
                    >
                      {dayKeys.map((day, i) => (
                        <div
                          key={day}
                          className="tl-day"
                          style={{
                            top: `${(i * blockApi.MINUTES_PER_DAY - timelineStart) * PX}px`,
                            height: `${blockApi.MINUTES_PER_DAY * PX}px`,
                          }}
                        >
                          <div className="tl-day-head">
                            <b className="tl-day-no">Day {i + 1}</b>
                            <span className="tl-day-date">
                              {dayDate(project, i) || "날짜 미정"}
                            </span>
                          </div>
                        </div>
                      ))}
                    </div>

                    {/* 다른 멤버들의 라이브 커서(타임라인 정밀 좌표) — 카드 위에 뜨되
                        클릭은 통과시킨다(pointer-events: none). 상태는 레이어가 자체 보유 */}
                    <RemoteCursorLayer
                      mode="tl"
                      register={registerTlCursorHandler}
                      myId={currentUser?.id}
                      activeDayNo={dayNoOf(activeDay)}
                      timelineStart={timelineStart}
                      px={PX}
                      padTop={TL_PAD_TOP}
                      padLeft={TL_PAD_LEFT}
                      nicknameOf={nicknameOf}
                    />
                  </div>
                </div>

                <div
                  className={`pool-sec ${dragPreview?.region === "pool" ? "dropover" : ""}`}
                  ref={setPoolRef}
                >
                  <div className="pool-head">
                    <div>
                      <b>후보 목록</b> <span className="n">{pool.length}</span>
                      {/* 사용 안내는 ⓘ 커스텀 툴팁으로 (QA 배치2) — 호버 즉시,
                          앱 디자인에 맞는 말풍선. 문구는 data-tip 이 CSS content 로 그린다 */}
                      <span
                        className="hint-ico"
                        tabIndex={0}
                        aria-label="후보 목록 사용 안내"
                        data-tip="블록을 끌어다 놓아 보관하는 공간이에요. 타임라인·후보 목록 밖에 놓으면 삭제됩니다."
                      >
                        ⓘ
                      </span>
                    </div>
                    <button
                      className="pool-add-btn"
                      onClick={handleCreateCustomBlock}
                    >
                      + 커스텀 블록 만들기
                    </button>
                  </div>
                  <div className="pool">
                    <SortableContext items={pool} strategy={rectSortingStrategy}>
                      {pool.map((id) => (
                        <PoolCard
                          key={id}
                          id={id}
                          item={items[id]}
                          onEditBlock={setEditingBlockId}
                          lockedBy={lockBadgeOf(id)}
                          editor={editorBadgeOf(id)}
                        />
                      ))}
                    </SortableContext>
                    {dragPreview?.region === "pool" && !isDraggingFromPool && (
                      <div className="pool-dropzone" />
                    )}
                    {/* 빈 상태 — 어디서 채우는지(챗봇·지도 검색)를 함께 안내한다.
                        드래그로 놓으려는 중에는 드롭존이 대신 보이므로 숨긴다 */}
                    {pool.length === 0 && dragPreview?.region !== "pool" && (
                      <div className="pool-empty">
                        <span className="pool-empty__ico" aria-hidden="true">
                          🗂️
                        </span>
                        <b className="pool-empty__title">
                          아직 보관한 블록이 없어요
                        </b>
                        <span className="pool-empty__desc">
                          오른쪽 <b>지도 검색</b>이나 <b>챗봇 이음이</b>의 추천을
                          끌어다 여기에 보관하고, <b>+ 커스텀 블록</b>으로 직접
                          만들 수도 있어요.
                        </span>
                      </div>
                    )}
                  </div>
                </div>
              </div>

              <div className="side">
                <BudgetPanel
                  totalBudget={totalBudget}
                  perPersonBudget={perPersonBudget}
                  headcount={headcount}
                  targetBudget={targetBudget}
                  budgetDraft={budgetDraft}
                  setBudgetDraft={setBudgetDraft}
                  commitBudgetDraft={commitBudgetDraft}
                  budgetEditCancelledRef={budgetEditCancelledRef}
                  bumpTargetBudget={bumpTargetBudget}
                  budgetPct={budgetPct}
                  remainingBudget={remainingBudget}
                  budgetSegments={budgetSegments}
                />

                <MapPanel
                  initMapOnContainer={initMapOnContainer}
                  pinPickMode={pinPickMode}
                  onCancelPinPick={cancelPinPick}
                />

                <SearchPanel
                  searchKeyword={searchKeyword}
                  setSearchKeyword={setSearchKeyword}
                  searchResults={searchResults}
                  searchListRef={searchListRef}
                  handleSearchPlace={handleSearchPlace}
                  handleClearSearch={handleClearSearch}
                  handlePlaceClick={handlePlaceClick}
                />
              </div>

              {/* 보드 밖 경고 — 후보 목록·타임라인 어느 쪽도 아닌 곳에 블록을
                  끌고 갔을 때 화면 가장자리에 빨간 테두리를 두르고, 여기서 놓으면
                  삭제된다고 알린다. 별도 휴지통 영역을 없앤 대신의 안전장치라
                  눈에 확 띄어야 한다. pointer-events 를 먹으면 드래그가 끊기므로
                  반드시 통과시킨다. */}
              {dragPreview?.region === "discard" && (
                <div className="discard-warning" aria-hidden="true">
                  <div className="discard-warning-label">
                    🗑 범위 밖에 놓으면 삭제됩니다
                  </div>
                </div>
              )}

              {/* 💡 끌려다니는 마우스 오버레이 부분 업데이트 */}
              <DragOverlay>
                {activeId && draggedItem ? (
                  isDraggingFromPool || isDraggingFromSearch ? (
                    <div
                      className="pcard is-overlay"
                      style={{
                        "--dc": catOf(draggedItem).hex,
                        "--cb": catOf(draggedItem).bg,
                      }}
                    >
                      <CardBody
                        id={draggedItem.id}
                        item={draggedItem}
                        mode="pool"
                      />
                    </div>
                  ) : (
                    <div
                      className="card is-overlay"
                      style={{
                        "--dc": catOf(draggedItem).hex,
                        "--cb": catOf(draggedItem).bg,
                      }}
                    >
                      <CardBody
                        id={draggedItem.id}
                        item={draggedItem}
                        mode="timeline"
                        startMins={items[activeId]?.startMins || 0}
                        endMins={
                          (items[activeId]?.startMins || 0) +
                          (draggedItem.dur || 0)
                        }
                      />
                    </div>
                  )
                ) : null}
              </DragOverlay>

              {/* 타임라인 밖(후보·사이드 등)의 라이브 커서 — 페이지 비율 좌표.
                  같은 Day 를 보는 멤버의 것만 그린다(다른 화면 위 커서는 착시) */}
              <RemoteCursorLayer
                mode="page"
                register={registerPageCursorHandler}
                myId={currentUser?.id}
                activeDayNo={dayNoOf(activeDay)}
                nicknameOf={nicknameOf}
              />

              {/* 챗봇 — 추천 카드를 후보 목록으로 드래그해야 하므로 반드시
                  이 DndContext 안에서 렌더한다 (위치는 fixed 라 화면상 그대로) */}
              <ChatbotWidget projectId={projectId} getMapBounds={getMapBounds} />
            </div>
          </DndContext>
        ) : (
          <ReadModeView
            board={board}
            items={items}
            dayKeys={dayKeys}
            project={project}
          />
        )}
      </div>

      {editingBlockId && items[editingBlockId] && (
        // 지도에서 위치를 찍는 동안엔 감추기만 한다(언마운트 아님) — 조건에서
        // 빼 버리면 폼 state 가 통째로 날아가 지정하러 가기 전 입력이 사라진다
        <div className={`blk-modal-ov${pinPickMode ? " is-hidden" : ""}`}>
          {(() => {
            const item = items[editingBlockId];
            const sMins = item.startMins;
            const eMins = sMins + item.dur;
            // Day 는 블록 자신의 오프셋에서 뽑는다 — 보고 있는 탭이 아니다.
            // 소속 규칙은 어디서나 시작 시각 기준(floor(offset/1440)+1)이다:
            // 서버 Block.dayNo(), 체인 소속, 챗봇 요약이 전부 그렇다.
            const dayNum = blockApi.dayNoOfOffset(sMins);
            // 자정을 넘는 블록은 "23:30 - 05:00" 이 하루 안에서 거꾸로 간 것처럼
            // 읽힌다 — 끝이 며칠 뒤인지 붙여 준다.
            const overDays =
              sMins == null ? 0 : blockApi.dayNoOfOffset(eMins) - dayNum;
            // 후보(POOL) 블록은 시각이 없다(느슨한 블록) — 폼이 "시간 정보 없음"을 띄운다
            const timeStr =
              sMins == null
                ? ""
                : `Day ${dayNum} · ${fmtTime(sMins)} - ${fmtTime(eMins)}${
                    overDays > 0 ? ` (+${overDays}일)` : ""
                  }`;

            const lockedByName = lockBadgeOf(editingBlockId);
            return (
              <BlockEditForm
                // 블록이 바뀌면 폼을 새로 만든다. formData 는 마운트 때 한 번만
                // initialData 로 씨를 뿌리므로, 같은 인스턴스를 재사용하면 A 의
                // 입력값을 든 채 저장 대상만 B 로 바뀌어 A 의 이름·비용·비고가
                // B 에 덮여 쓰인다. 지정 모드에선 모달을 감추기만 해서(언마운트
                // 아님) 그 동안 보드가 클릭 가능해졌고, 그래서 A→B 전환이 실제로
                // 닿을 수 있는 경로가 됐다. key 는 pinPickMode 로는 안 바뀌므로
                // "지정 중에도 폼을 살려 둔다"는 성질은 그대로다.
                key={editingBlockId}
                initialData={item}
                timeString={timeStr}
                // 서버가 category 필드 갱신을 지원하지 않는다(BLOCK400_2) —
                // 카테고리는 생성 시에만 정할 수 있다
                categoryLocked={!isTempId(editingBlockId)}
                // advisory 락 — 편집을 막지 않고 동시 편집 사실만 알린다.
                // 세부 내용(detail)은 마지막 저장이 통째로 이기므로 겹치면 유실될 수 있다.
                lockNotice={
                  lockedByName
                    ? `✎ ${lockedByName} 님도 이 블록을 편집하고 있어요`
                    : ""
                }
                pinnedLocation={pinnedLocation}
                onRequestPinPick={handleRequestPinPick}
                onSave={handleSaveBlock}
                onCancel={handleCancelEdit}
                onReselectTransport={handleReselectTransport}
              />
            );
          })()}
        </div>
      )}

      <TransitPickerModals
        items={items}
        bulkTransitPicker={bulkTransitPicker}
        setBulkTransitPicker={setBulkTransitPicker}
        setBulkChoice={setBulkChoice}
        confirmBulkTransit={confirmBulkTransit}
        transitPicker={transitPicker}
        setTransitPicker={setTransitPicker}
        setTransitPickerCandidate={setTransitPickerCandidate}
        confirmTransitChoice={confirmTransitChoice}
        transportReselectPicker={transportReselectPicker}
        setTransportReselectPicker={setTransportReselectPicker}
        setReselectCandidate={setReselectCandidate}
        applyReselectTransport={applyReselectTransport}
      />

      {/* 보이스 위젯 — 화면 맨 아래 가장자리에 붙은 탭(Vue DevTools 의 그 탭처럼).
          평소엔 윗부분만 빼꼼 보이다가 올리면 다 나오고, 누르면 그 위로 마이크·
          스피커 아이콘이 펼쳐진다. 입장하면 자동 연결(권한 거부 시 듣기 전용)이고,
          버튼은 송신(마이크)·수신(스피커)만 끄고 켠다 — 접어 둬도 연결은
          대시보드를 떠날 때까지 유지된다.
          모바일에서는 통째로 빠진다 — 읽기 전용 화면이라 함께 편집하며 통화할
          일이 없고, 좁은 화면에서 하단 공간을 챗봇 버튼과 나눠 쓰기도 빠듯하다. */}
      {!isMobile && (
        <VoiceBar
          voice={voice}
          voiceOpen={voiceOpen}
          setVoiceOpen={setVoiceOpen}
          currentUser={currentUser}
          boardMembers={boardMembers}
        />
      )}

      {/* 프로젝트 수정 — 그룹 페이지의 ✎ 와 같은 모달을 그대로 쓴다.
          저장 뒤에는 스냅샷을 다시 읽어야 제목·Day 탭·기간이 따라온다
          (이 화면의 project 원천은 목록이 아니라 스냅샷이다). */}
      {editProjectOpen && project && (
        <EditProjectModal
          open
          project={project}
          onUpdate={updateProject}
          onSaved={reload}
          onClose={() => setEditProjectOpen(false)}
        />
      )}
    </>
  );
}
