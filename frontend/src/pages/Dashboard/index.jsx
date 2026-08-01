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
  useSortable,
  rectSortingStrategy,
} from "@dnd-kit/sortable";
import { CSS } from "@dnd-kit/utilities";
import { generateKeyBetween } from "fractional-indexing";
import { AppBar } from "../My/shared/ui/AppBar";
import { useDashboard } from "../../features/dashboard/hooks/useDashboard";
import * as blockApi from "../../features/dashboard/api/dashboardApi";
import { useGroupDetail } from "../../features/group/hooks/useGroupDetail";
import { useProjects } from "../../features/group/hooks/useProjects";
import { useAuthStore } from "../../global/stores/authStore";
import { useToastStore } from "../../global/stores/toastStore";
import "./index.css";

const PX = 2.0;
const SNAP = 10;
const DAY_END = 1440;
const TL_PAD_TOP = 20;
const TL_PAD_LEFT = 70;

/**
 * 카테고리(대분류) 표. 색은 값을 직접 적지 않고 공통 토큰(tokens.css)을 가리킨다 —
 * 팔레트를 바꿀 일이 생기면 CSS 한 곳만 고치면 된다.
 * hex/bg 는 그대로 CSS 변수(--dc/--cb)나 배경색으로 넘어가므로 var() 문자열로 둔다.
 */
const CAT_COLORS = {
  stay: { nm: "숙소", hex: "var(--stay, #8a5aa8)", bg: "var(--stayB, #f3edfa)" },
  food: { nm: "식당", hex: "var(--food, #d97e3c)", bg: "var(--foodB, #fdf1e4)" },
  spot: {
    nm: "명소/활동",
    hex: "var(--spot, #3e8e63)",
    bg: "var(--spotB, #eaf5ec)",
  },
  etc: { nm: "기타", hex: "var(--etc, #7a6a5c)", bg: "var(--etcB, #f1ece4)" },
  trans: {
    nm: "교통",
    hex: "var(--trans, #6b7fc7)",
    bg: "var(--transB, #eef0fb)",
  },
};

const fmtTime = (mins) => {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
};

const won = (n) => (n ? n.toLocaleString("ko-KR") + "원" : "무료");
const catOf = (item) => CAT_COLORS[item?.cat] || CAT_COLORS.etc;

// ── 블록 id 규약 ─────────────────────────────────────
// 서버에 아직 없는 블록을 구분하는 규약 — custom-(모달 저장 전), search-(생성 요청 중)
const isTempId = (id) =>
  String(id).startsWith("custom-") || String(id).startsWith("search-");
// 서버에 실재하는 블록만 REST 를 태운다 — 임시 id·auto-(로컬 교통)는 제외
const isServerBlock = (id) => !isTempId(id) && !String(id).startsWith("auto-");

// 카카오 category_group_code → 화면 cat. 음식점(FD6)·카페(CE7)는 food,
// 숙박(AD5)은 stay, 그 외 장소는 spot 으로 본다.
const catFromKakaoGroup = (code) => {
  if (code === "FD6" || code === "CE7") return "food";
  if (code === "AD5") return "stay";
  return "spot";
};

// "d3" → 3 (서버 dayNo)
const dayNoOf = (dayKey) => Number(String(dayKey).replace("d", ""));

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
 * 겹침 해소(resolveOverlaps)로 시각이 밀린 체인 내 서버 블록들의 시각을 저장한다.
 * 편집(3단계)·이동(5단계)·리사이즈가 공유한다 — 로컬만 밀면 새로고침 때
 * 이웃들이 옛 시각으로 되돌아간다(명세 320행: 시각 재계산 저장은 클라이언트 몫).
 */
const persistShiftedTimes = (chainIds, prevItems, nextItems, excludeId) => {
  const shifted = (chainIds ?? []).filter(
    (id) =>
      id !== excludeId &&
      isServerBlock(id) &&
      nextItems[id]?.startMins != null &&
      nextItems[id].startMins !== prevItems[id]?.startMins,
  );
  return Promise.all(
    shifted.map((id) =>
      blockApi.updateBlockFields(id, {
        startTime: blockApi.minsToTime(nextItems[id].startMins),
        endTime: blockApi.minsToTime(
          nextItems[id].startMins + nextItems[id].dur,
        ),
      }),
    ),
  );
};

const DAY_MS = 24 * 60 * 60 * 1000;
/** 프로젝트를 아직 못 불러왔을 때만 쓰는 Day 수 (기존 목업과 같은 4일). */
const FALLBACK_DAY_COUNT = 4;
const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

// 'YYYY-MM-DD' 를 그냥 new Date() 에 넣으면 UTC 자정으로 읽혀 KST에서 하루 밀린다.
const parseDate = (iso) =>
  typeof iso === "string" && iso.length >= 10
    ? new Date(`${iso.slice(0, 10)}T00:00:00`)
    : null;

/**
 * 프로젝트 기간(startDate~endDate)에서 Day 키 목록을 만든다.
 * 그룹 페이지에서 기간을 수정하면 이 목록이 바뀌고, Day 탭·읽기 모드가 함께 따라간다.
 */
function dayKeysOf(project) {
  const start = parseDate(project?.startDate);
  const end = parseDate(project?.endDate);
  const count =
    start && end
      ? Math.min(30, Math.max(1, Math.round((end - start) / DAY_MS) + 1))
      : FALLBACK_DAY_COUNT;
  return Array.from({ length: count }, (_, i) => `d${i + 1}`);
}

/** Date → 'YYYY-MM-DD' (서버·<input type="date"> 가 쓰는 형식). */
function toISODate(date) {
  const mm = String(date.getMonth() + 1).padStart(2, "0");
  const dd = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${mm}-${dd}`;
}

/** Day n(0-based)의 날짜를 'YYYY-MM-DD' 로. 기간을 모르면 빈 문자열. */
function dayISODate(project, index) {
  const start = parseDate(project?.startDate);
  return start ? toISODate(new Date(start.getTime() + index * DAY_MS)) : "";
}

/** Day n(0-based)의 실제 날짜. 기간을 모르면 빈 문자열 — 가짜 날짜를 만들지 않는다. */
function dayDate(project, index, style = "full") {
  const start = parseDate(project?.startDate);
  if (!start) return "";
  const d = new Date(start.getTime() + index * DAY_MS);
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  if (style === "short") return `${mm}.${dd}`;
  return `${d.getFullYear()}.${mm}.${dd} (${WEEKDAYS[d.getDay()]})`;
}

/** 없는/잘못된 cat 은 기타로 모은다 — 카테고리 합계에서 한 칸이 사라지지 않게. */
const catKeyOf = (item) => (CAT_COLORS[item?.cat] ? item.cat : "etc");

const resolveOverlaps = (currentItems, dayChain, dayStartMins, fixedId) => {
  let newItems = { ...currentItems };
  const others = dayChain.filter((id) => id !== fixedId);
  others.sort((a, b) => newItems[a].startMins - newItems[b].startMins);

  const fixedStart = fixedId ? newItems[fixedId].startMins : -1;
  const fixedEnd = fixedId ? fixedStart + newItems[fixedId].dur : -1;

  let lastEnd = dayStartMins;

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

  const newChain = [...dayChain].sort(
    (a, b) => newItems[a].startMins - newItems[b].startMins,
  );
  return { newItems, newChain };
};

/**
 * 다이어리 책갈피 모양의 Day 탭.
 * 모양(리본 노치·보드 아래로 끼워지는 느낌)은 CSS(.day-tab)에서 만들고 여기서는
 * 책갈피에 적히는 세 줄 — Day 번호 / 날짜 / 블록 수 — 만 담는다.
 */
function DayTab({ label, date, count, isActive, onClick }) {
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
    </button>
  );
}

function CardBody({ item, mode, startMins, endMins, isThisResizing, onEdge }) {
  const catStyle = catOf(item);

  if (mode !== "timeline") {
    return (
      <>
        <div className="l">
          <span className="cat">
            {catStyle.nm}
            {item?.sub ? ` · ${item.sub}` : ""}
          </span>
          <span className="grip">⠿</span>
        </div>
        {/* 💡 연필 아이콘 삭제, 글씨 두께만 강조 */}
        <div className="nm">{item?.name}</div>
        <div className="sub">{item?.detail || item?.address}</div>
      </>
    );
  }

  return (
    <>
      {onEdge && (
        <div
          className={`tl-edge is-top ${isThisResizing === "top" ? "is-active" : ""}`}
          onClick={(e) => onEdge(e, "top")}
        />
      )}
      <div className="l1">
        <span className="cat">
          {catStyle.nm}
          {item?.sub ? ` · ${item.sub}` : ""}
        </span>
        {item?.auto && <span className="auto-badge">자동</span>}
        <span>
          <span className="nm">{item?.name}</span>{" "}
          <span className="nm-sub">{item?.detail}</span>
        </span>
        <span className="time">
          {fmtTime(startMins)} – {fmtTime(endMins)}
        </span>
        <span className="cost">{won(item?.cost)}</span>
      </div>
      <div className="addr">📍 {item?.address || "위치 정보 없음"}</div>
      <div className="ctl">
        {/* 리사이즈 중에는 안내 문구가 카테고리 색으로 강조된다(.dur.is-resizing) */}
        <span className={`dur ${isThisResizing ? "is-resizing" : ""}`}>
          {isThisResizing
            ? "마우스를 움직여 조절 후 클릭하여 확정"
            : `소요 ${item?.dur}분`}
        </span>
      </div>
      {onEdge && (
        <div
          className={`tl-edge is-bottom ${isThisResizing === "bottom" ? "is-active" : ""}`}
          onClick={(e) => onEdge(e, "bottom")}
        />
      )}
    </>
  );
}

/**
 * 블록의 수정 표시. 개인 페이지의 카드(.g-card .op)와 같은 ✎ 아이콘·같은 규칙 —
 * 평소에는 숨어 있고 카드에 마우스를 올릴 때만 나타난다(노출은 CSS .blk-op).
 *
 * 카드 전체에 드래그 리스너가 걸려 있어 pointerdown 을 여기서 끊어야 버튼을 누르는
 * 동작이 드래그 시작으로 오해되지 않는다.
 */
function BlockEditBadge({ onEdit }) {
  if (!onEdit) return null;
  return (
    <button
      type="button"
      className="blk-op"
      title="블록 수정"
      aria-label="블록 수정"
      onPointerDown={(e) => e.stopPropagation()}
      onClick={(e) => {
        e.stopPropagation();
        onEdit();
      }}
    >
      ✎
    </button>
  );
}

function PoolCard({ id, item, onEditBlock }) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id, data: { from: "pool" } });
  const catStyle = catOf(item);
  // dnd-kit 이 만들어주는 이동값과 카테고리 색만 인라인으로 넘긴다(색 지정은 CSS 몫).
  const style = {
    transform: isDragging ? undefined : CSS.Transform.toString(transform),
    transition,
    "--dc": catStyle.hex,
    "--cb": catStyle.bg,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`pcard ${isDragging ? "is-dragging" : ""}`}
      data-pool-id={id}
      {...attributes}
      {...listeners}
      // 💡 박스 전체 영역에 클릭 이벤트 연결
      onClick={() => onEditBlock && onEditBlock(id)}
    >
      <CardBody id={id} item={item} mode="pool" onEditBlock={onEditBlock} />
      <BlockEditBadge onEdit={onEditBlock && (() => onEditBlock(id))} />
    </div>
  );
}

function TimelineCard({
  id,
  item,
  startMins,
  endMins,
  resizingState,
  onResizeStart,
  dayStartMins,
  boundTop,
  onEditBlock,
}) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id,
    data: { from: "timeline" },
  });
  const catStyle = catOf(item);
  const height = (item?.dur || 30) * PX;
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
  const topPx = (startMins - dayStartMins) * PX;
  const slotStyle = {
    "--dc": catStyle.hex,
    "--cb": catStyle.bg,
    top: `${topPx}px`,
    height: `${height}px`,
  };

  return (
    <div
      className={`slot ${isDragging ? "is-dragging" : ""} ${isThisResizing ? "is-resizing" : ""}`}
      style={slotStyle}
    >
      <span className="tlab">{fmtTime(startMins)}</span>
      <span className="dot" />
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
        />
        {!isThisResizing && (
          <BlockEditBadge onEdit={onEditBlock && (() => onEditBlock(id))} />
        )}
      </div>
    </div>
  );
}

// 💡 새롭게 추가된 검색 결과용 드래그 컴포넌트
function SearchResultDraggable({ place, onClick }) {
  const id = `search-result-${place.id}`;
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id,
    data: { from: "search", place },
  });

  return (
    <div
      ref={setNodeRef}
      className={`sr-item ${isDragging ? "is-dragging" : ""}`}
      {...attributes}
      {...listeners}
      onClick={() => onClick && onClick(place)}
    >
      <div className="sr-main">
        <div className="sr-dot">●</div>
        <div>
          <div className="sr-name">{place.place_name}</div>
          <div className="sr-addr">
            {place.road_address_name || place.address_name}
          </div>
          <div className="sr-cat">{place.category_group_name}</div>
        </div>
      </div>
      {/* 끌어다 놓기 유도용 손잡이 아이콘 */}
      <div className="sr-grip">⠿</div>
    </div>
  );
}

function ReadModeView({ chains, items, dayKeys, project }) {
  // 배경·좌우 여백은 편집 모드와 같은 껍데기(.dash-shell/.dash-body)가 쥔다 —
  // 여기서 배경을 따로 칠하면 모드를 바꿀 때 화면이 갈라져 보인다.
  return (
    <div className="dash-body read-view">
      {dayKeys.map((day, index) => {
        const chain = chains[day] || [];
        if (chain.length === 0) return null;
        return (
          <div key={day} className="rv-day">
            <h2 className="rv-day-title">
              Day {index + 1}{" "}
              <span className="rv-day-date">
                {dayDate(project, index) || "날짜 미정"}
              </span>
            </h2>

            <div className="rv-list">
              <div className="rv-line" />
              {chain.map((id) => {
                const item = items[id];
                if (!item) return null;
                const startMins = item.startMins;
                const endMins = startMins + item.dur;
                const catStyle = catOf(item);
                return (
                  // 카테고리 색만 CSS 변수로 넘기고, 그 색을 어디에 쓸지는 CSS 가 정한다
                  <div
                    key={id}
                    className="rv-row"
                    style={{ "--dc": catStyle.hex, "--cb": catStyle.bg }}
                  >
                    <div className="rv-time">{fmtTime(startMins)}</div>
                    <div className="rv-dot" />
                    <div className="rv-card">
                      <div className="rv-card-main">
                        <span className="rv-badge">
                          {catStyle.nm} {item.sub ? `· ${item.sub}` : ""}
                        </span>
                        <div>
                          <div className="rv-name">{item.name}</div>
                          <div className="rv-addr">
                            📍 {item.address || "위치 정보 없음"}
                          </div>
                        </div>
                      </div>
                      <div className="rv-card-side">
                        <div className="rv-range">
                          {fmtTime(startMins)} - {fmtTime(endMins)}
                        </div>
                        <div className="rv-cost">{won(item.cost)}</div>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function DashboardPage() {
  const { groupId } = useParams();
  // 라우트 파라미터는 문자열 — 서버의 숫자 ID와 맞추려면 변환이 필요하다 (GroupPage 와 동일)
  const projectId = Number(useParams().projectId);
  const navigate = useNavigate();
  const showToast = useToastStore((s) => s.show);

  // 스냅샷은 훅이 소유한다(1단계 — 읽기 연동). 아래 items/chains/pool 편집 상태는
  // 아직 로컬이다: 드래그·수정 결과의 서버 저장은 2~5단계 mutation 에서 붙는다.
  // 그래서 지금 구조는 "로딩 완료 시 서버 보드를 로컬 상태로 시드"이며,
  // 새로고침하면 서버 상태로 되돌아간다(로컬 편집은 아직 휘발).
  const {
    project,
    items: serverItems,
    chains: serverChains,
    pool: serverPool,
    status,
    error,
    reload,
  } = useDashboard(projectId);

  // 상단바(개인 페이지 › 그룹명 › 프로젝트명)의 그룹명·멤버 아바타는 그룹 상세에서,
  // 접속자 표시는 로그인 사용자에서 얻는다. 프로젝트 데이터(이름·기간·예산)의
  // 원천은 위 스냅샷 하나다 — 두 소스가 섞이면 날짜 수정 직후 값이 어긋난다.
  const numericGroupId = Number(groupId);
  const { group } = useGroupDetail(numericGroupId);
  // 날짜 캘린더(handleDayDateChange)의 저장 경로로만 쓴다 — 저장 후 reload() 로
  // 스냅샷을 다시 읽어야 이 화면의 Day 탭·날짜가 따라 바뀐다.
  const { updateProject } = useProjects(numericGroupId);
  const currentUser = useAuthStore((s) => s.currentUser);

  // 여행 기간이 곧 Day 개수다. 기간이 바뀌면(날짜 캘린더·그룹 페이지 수정 후 재조회)
  // 탭도 함께 바뀐다.
  const dayKeys = useMemo(() => dayKeysOf(project), [project]);

  const [viewMode, setViewMode] = useState("edit");
  const [dateEditOpen, setDateEditOpen] = useState(false); // 날짜 캘린더 열림 여부
  const [selectedDay, setActiveDay] = useState("d1");
  // 기간이 줄어 보고 있던 Day 가 사라지면 첫째 날을 본다 — 상태를 되돌리지 않고
  // 렌더 시점에 정하므로 "없는 Day 를 가리키는 한 프레임"이 생기지 않는다.
  const activeDay = dayKeys.includes(selectedDay) ? selectedDay : dayKeys[0];
  const activeDayIndex = Math.max(0, dayKeys.indexOf(activeDay));

  // Day 시작 시각(타임라인 상단) — 서버에 저장 칸이 없는(ERD) 본인 화면 전용 값.
  // 기본 09:00 고정이고 상단 ± 버튼으로만 바뀐다. 블록 시각에서 파생하지 않는다 —
  // 파생하면 블록을 놓을 때마다 새로고침 후 타임라인 시작이 멋대로 움직인다.
  // 초기값은 비워 두고 스냅샷 시드가 Day 별로 채운다. 시드에 없는 Day(기간 연장 등)는
  // 읽는 쪽이 전부 `?? 540` 으로 받는다.
  const [dayStart, setDayStart] = useState({});

  // 보드 편집 상태 — 초기값은 비워 두고, 스냅샷이 도착하면 아래 시드 effect 가 채운다.
  const [items, setItems] = useState({});
  const [chains, setChains] = useState({});
  const [pool, setPool] = useState([]);

  // 기간이 줄어 사라진 Day 에 남아 있던 블록은 버리지 않고 후보 목록으로 되돌린다 —
  // 서버가 PATCH 응답의 movedToPool 로 알려주는 것과 같은 규칙이다.
  // (늘어난 Day 는 상태를 만들 필요가 없다. 조회하는 쪽이 전부 `chains[day] || []`,
  //  `dayStart[day] ?? 540` 로 비어 있는 경우를 받아낸다.)
  // 시드와 같은 "렌더 중 조건부 setState" 패턴 — effect 로 하면 set-state-in-effect 에
  // 걸리고, 스냅샷 재시드와 같은 렌더에 겹칠 때도 아래 시드 블록이 나중에 실행되므로
  // 서버 진실이 이긴다.
  const goneDays = Object.keys(chains).filter((key) => !dayKeys.includes(key));
  if (goneDays.length > 0) {
    const dropped = goneDays.flatMap((key) => chains[key]);
    if (dropped.length > 0) {
      setPool((p) => [...dropped.filter((id) => !p.includes(id)), ...p]);
    }
    const next = {};
    dayKeys.forEach((key) => {
      next[key] = chains[key] ?? [];
    });
    setChains(next);
  }

  const [editingBlockId, setEditingBlockId] = useState(null);
  const [activeId, setActiveId] = useState(null);
  const [resizingState, setResizingState] = useState(null);
  const [dragPreview, setDragPreview] = useState(null);
  const [isGeneratingTransport, setIsGeneratingTransport] = useState(false);

  const [map, setMap] = useState(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [searchResults, setSearchResults] = useState([]);
  const searchListRef = useRef(null);
  const infoWindowRef = useRef(null);

  // 지도 초기화 — 컨테이너 div 의 ref callback 으로 한다.
  //
  // "마운트 시 1회 effect + getElementById" 방식은 레이스가 있었다: 스냅샷 로딩
  // 가드가 null 을 반환하는 동안에는 컨테이너가 DOM 에 없어서, SDK 가 이미 로드된
  // 재진입(그룹 페이지에서 되돌아오기 등)에서는 initMap 이 빈손으로 끝나고 지도가
  // 영영 회색으로 남았다. ref callback 은 "div 가 실제로 마운트된 순간"에 불리므로
  // 레이스가 없고, 읽기 모드 전환으로 div 가 재마운트될 때도 다시 바인딩된다.
  const initMapOnContainer = useCallback((container) => {
    if (!container) {
      // 언마운트(읽기 모드 전환 등) — 카카오 지도는 destroy API 가 없어
      // 참조만 끊는다. 다음 마운트 때 새 인스턴스로 바인딩된다.
      setMap(null);
      return;
    }

    const bind = () => {
      window.kakao.maps.load(() => {
        const newMap = new window.kakao.maps.Map(container, {
          center: new window.kakao.maps.LatLng(33.450701, 126.570667),
          level: 7,
        });
        setMap(newMap);
      });
    };

    const existing = document.getElementById("kakao-map-script");
    if (existing) {
      // 스크립트 태그는 있는데 아직 로딩 중일 수 있다 — 그때는 load 를 기다린다
      if (window.kakao?.maps) bind();
      else existing.addEventListener("load", bind, { once: true });
      return;
    }

    const script = document.createElement("script");
    script.id = "kakao-map-script";
    // 💡 autoload=false 파라미터가 반드시 있어야 리액트와 충돌하지 않습니다!
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=71b94eabee0913242230da390f4d20f2&autoload=false&libraries=services`;
    script.async = true;
    script.onload = bind;
    document.head.appendChild(script);
  }, []);

  // 지도 패널이 사이드 폭을 그대로 쓰게 되면서(빈 공간 활용) 창 크기에 따라 실제
  // 픽셀 크기가 바뀐다 — 카카오 지도는 컨테이너 크기가 변하면 relayout() 을 불러줘야
  // 타일과 중심이 어긋나지 않는다.
  useEffect(() => {
    if (!map) return;
    const onResize = () => map.relayout();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, [map]);

  // 날짜 팝오버는 Esc 로 닫는다. 브라우저 캘린더를 자동으로 띄우지는 않는다 —
  // 팝오버가 열리자마자 캘린더가 겹쳐 뜨면 시야를 가려서, 입력칸의 달력 표시를
  // 눌렀을 때만 열리게 둔다.
  useEffect(() => {
    if (!dateEditOpen) return;
    const onKey = (e) => e.key === "Escape" && setDateEditOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [dateEditOpen]);

  /**
   * 캘린더에서 고른 날짜를 지금 보고 있는 Day 의 날짜로 삼는다.
   *
   * 여행은 이어진 기간이라 Day 하나만 다른 날로 뗄 수 없다 — 고른 날짜에 이 Day 가 오도록
   * 시작일·종료일을 통째로 옮긴다. 기간 길이는 그대로여서 Day 수와 블록 배치가 흔들리지
   * 않는다. (며칠짜리 여행인지 자체를 바꾸는 건 그룹 페이지의 수정 폼에서 한다.)
   */
  async function handleDayDateChange(picked) {
    setDateEditOpen(false);
    const pickedDate = parseDate(picked);
    if (!pickedDate || !project) return;

    const newStart = new Date(pickedDate.getTime() - activeDayIndex * DAY_MS);
    const newEnd = new Date(newStart.getTime() + (dayKeys.length - 1) * DAY_MS);
    const startISO = toISODate(newStart);
    const endISO = toISODate(newEnd);
    if (startISO === project.startDate && endISO === project.endDate) return;

    try {
      await updateProject(projectId, {
        name: project.name,
        startDate: startISO,
        endDate: endISO,
      });
      // 이 화면의 project 원천은 스냅샷이다 — 다시 읽어야 Day 탭·날짜가 따라온다.
      // (기간 밖으로 밀려난 블록의 후보 이동도 서버 응답이 진실이다.)
      reload();
      showToast(`여행 일정을 ${startISO} 시작으로 옮겼어요 ✓`);
    } catch {
      showToast("날짜를 바꾸지 못했어요. 잠시 후 다시 시도해주세요.");
    }
  }

  const handleSearchPlace = (e) => {
    e.preventDefault();
    if (!searchKeyword.trim()) {
      alert("검색어를 입력해주세요.");
      return;
    }
    if (!window.kakao || !window.kakao.maps || !window.kakao.maps.services) {
      alert("카카오 장소 검색 API를 사용할 수 없습니다.");
      return;
    }

    const ps = new window.kakao.maps.services.Places();
    ps.keywordSearch(searchKeyword, (data, status) => {
      if (status === window.kakao.maps.services.Status.OK) {
        // 검색 결과 데이터 업데이트
        setSearchResults(data);

        // ✅ 디테일 1: 새 검색을 하면 스크롤을 맨 위로 휙! 올리기
        if (searchListRef.current) {
          searchListRef.current.scrollTop = 0;
        }

        // ✅ 디테일 2 (보너스): 새 검색을 하면 기존에 열려있던 정보 창(말풍선) 닫기!
        if (infoWindowRef.current) {
          infoWindowRef.current.close();
        }

        // 지도 화면 범위를 새 검색 결과들에 맞게 이동시키기
        // (기존 handleSearchPlace 함수 내부의 if (map) 안쪽 로직)
        if (map) {
          const bounds = new window.kakao.maps.LatLngBounds();

          // 💡 이 forEach 부분을 통째로 교체해 주세요!
          data.forEach((place) => {
            const position = new window.kakao.maps.LatLng(place.y, place.x);
            bounds.extend(position);

            // 1. 마커를 변수에 담아서 생성
            const marker = new window.kakao.maps.Marker({
              map: map,
              position: position,
            });

            // 2. 💡 생성된 마커에 클릭 이벤트(클릭 시 상세정보 띄우기) 연결!
            window.kakao.maps.event.addListener(marker, "click", () => {
              handlePlaceClick(place);
            });
          });

          map.setBounds(bounds);
        }
      } else if (status === window.kakao.maps.services.Status.ZERO_RESULT) {
        alert("검색 결과가 존재하지 않습니다.");
        setSearchResults([]);
      } else {
        alert("검색 중 오류가 발생했습니다.");
      }
    });
  };
  const handlePlaceClick = (place) => {
    if (map && window.kakao && window.kakao.maps) {
      const moveLatLon = new window.kakao.maps.LatLng(place.y, place.x);

      map.setLevel(4);
      map.panTo(moveLatLon);

      // 💡 2-1. 인포윈도우가 아직 안 만들어졌다면 최초 1회 생성
      if (!infoWindowRef.current) {
        infoWindowRef.current = new window.kakao.maps.InfoWindow({
          zIndex: 1,
          removable: true, // 창 닫기(X) 버튼 활성화
        });
      }

      // 💡 2-2. 정보 창 안에 들어갈 디자인(HTML) 구성
      // 현재 앱의 테마 색상(#d97e3c 등)을 사용해 통일감을 주었습니다.
      const content = `
        <div style="padding:15px; font-size:13px; color:#333; min-width:200px; border-radius:8px;">
          <b style="font-size:15px; display:block; margin-bottom:5px; color:#d97e3c;">${place.place_name}</b>
          ${place.road_address_name ? `<span style="display:block;">${place.road_address_name}</span>` : ""}
          <span style="color:#888; display:block; margin-top:2px;">${place.address_name}</span>
          ${place.phone ? `<span style="display:block; margin-top:5px; color:#6b7fc7;">📞 ${place.phone}</span>` : ""}
        </div>
      `;

      // 💡 2-3. 내용과 좌표를 갱신하고 지도에 열기
      infoWindowRef.current.setContent(content);
      infoWindowRef.current.setPosition(moveLatLon);
      infoWindowRef.current.open(map);
    }
  };

  const totalBudget = Object.values(items).reduce(
    (sum, item) => sum + (item.cost || 0),
    0,
  );
  const [targetBudget, setTargetBudget] = useState(0);

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
    setChains(serverChains);
    setPool(serverPool);

    // Day 시작 시각은 09:00 고정 — 버튼으로만 바뀐다(로컬 값이라 새로고침 시 초기화).
    // 단 09:00 이전에 시작하는 블록이 있으면 타임라인 위로 잘려 안 보이므로,
    // 그런 Day 에 한해 가장 이른 블록 시각까지만 내려서 맞춘다.
    const starts = {};
    for (const [dayKey, chain] of Object.entries(serverChains)) {
      let start = 540;
      for (const id of chain) {
        const s = serverItems[id]?.startMins;
        if (s != null && s < start) start = s;
      }
      starts[dayKey] = start;
    }
    setDayStart(starts);

    // 다른 프로젝트에서 넘어온 경우 이전 프로젝트의 Day 탭이 남지 않게 한다
    if (!serverChains[activeDay]) setActiveDay("d1");

    // 목표 예산은 스냅샷의 project 에 실려 오고, 수정은 PATCH /projects 로 저장된다
    // (백엔드 합의로 targetBudget 필드 추가 — handleTargetBudgetChange 참조).
    setTargetBudget(project?.targetBudget ?? 0);
  }

  // 없는 프로젝트·비멤버·잘못된 URL 이면 그룹 페이지로 되돌린다 (GroupPage 와 같은 규칙)
  useEffect(() => {
    if (status !== "error") return;
    showToast(error?.message ?? "프로젝트를 열 수 없어요.");
    navigate(`/groups/${groupId}`, { replace: true });
  }, [status, error, groupId, navigate, showToast]);
  // 목표 예산 저장은 디바운스한다 — ± 버튼 연타(만원 단위)를 요청 1건으로 모은다.
  // 타이머가 언마운트 후에 발화해도 요청은 그대로 나간다(마지막 조작 유실 방지).
  const targetBudgetTimerRef = useRef(null);
  const handleTargetBudgetChange = (amount) => {

    const next = Math.max(0, targetBudget + amount); // 0원 밑으로는 안 내려가게 방지
    setTargetBudget(next);

    clearTimeout(targetBudgetTimerRef.current);
    targetBudgetTimerRef.current = setTimeout(() => {
      blockApi
        .updateTargetBudget(projectId, next)
        .catch(rollbackToServer);
    }, 600);
  };
  const budgetPercent =
    targetBudget > 0 ? Math.min(100, (totalBudget / targetBudget) * 100) : 0;
  const remainingBudget = targetBudget - totalBudget;

  /**
   * 카테고리(대분류)별 예산 세그먼트.
   *
   * 사용량 전체가 갈색 한 덩어리였을 때는 "무엇이 예산을 잡아먹는지"를 알 수 없었다.
   * 블록 하나하나로 쪼개면 칸이 너무 잘게 나뉘므로, 숙소·식당·명소/활동·기타·교통
   * 다섯 대분류로 합산해 카테고리 색 그대로 쌓는다.
   *
   * 칸의 폭은 "희망 예산 대비 비율"이다 — 그래야 남은 예산(빈 트랙)과 같은 자를 쓴다.
   * 예산을 넘긴 경우에는 기준을 총 사용액으로 바꿔 막대를 꽉 채우고, 초과분은 아래
   * 텍스트가 알려준다(비율이 100%를 넘는 칸은 그릴 수 없으므로).
   *
   * 순서는 금액순이 아니라 CAT_COLORS 선언 순서다 — 블록을 하나 고칠 때마다 칸이
   * 자리를 바꾸면 눈으로 따라가기 어렵다.
   */
  const budgetSegments = useMemo(() => {
    const denominator =
      remainingBudget < 0 || targetBudget <= 0
        ? totalBudget || 1
        : targetBudget;

    const sumByCat = {};
    Object.values(items).forEach((item) => {
      const cost = item?.cost || 0;
      if (cost <= 0) return;
      const cat = catKeyOf(item);
      sumByCat[cat] = (sumByCat[cat] ?? 0) + cost;
    });

    return Object.keys(CAT_COLORS)
      .filter((cat) => sumByCat[cat] > 0)
      .map((cat) => ({
        cat,
        name: CAT_COLORS[cat].nm,
        color: CAT_COLORS[cat].hex,
        cost: sumByCat[cat],
        percent: (sumByCat[cat] / denominator) * 100,
        shareOfTotal:
          totalBudget > 0 ? (sumByCat[cat] / totalBudget) * 100 : 0,
      }));
  }, [items, targetBudget, totalBudget, remainingBudget]);

  // 임시 목업 — 6단계에서 GET /api/transit/route 로 교체한다(BUS/SUBWAY만 구현됨).
  // 인자(출발/도착 블록)는 그때 다시 받는다.
  const fetchTransitInfo = useCallback(async () => {
    await new Promise((resolve) => setTimeout(resolve, 120));
    return { mode: "이동", dur: 20, cost: 0 };
  }, []);

  // 저장 실패 시 롤백 — "어디서 왔는지"를 복원하는 대신 서버 진실로 보드를
  // 다시 시드한다. 5.5단계 이후엔 교통 블록까지 전부 서버에 있으므로
  // reload 로 잃는 것이 없다.
  const rollbackToServer = useCallback(
    (e) => {
      showToast(
        e?.message ?? "변경을 저장하지 못했어요. 서버 상태로 되돌립니다.",
      );
      reload();
    },
    [showToast, reload],
  );

  // 임시 id 로 만든 로컬 블록을 서버 blockId 로 교체한다 (items + pool + chains).
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
    setChains((prev) => {
      const next = { ...prev };
      for (const day of Object.keys(next)) {
        next[day] = next[day].map((id) => (id === tempId ? blockId : id));
      }
      return next;
    });
  }, []);

  const regenerateAutoTransport = useCallback(
    async (dayKey) => {
      const chain = chains[dayKey] || [];
      const realIds = chain.filter((id) => !items[id]?.auto);
      // 재생성 = 이 Day 의 기존 자동 생성분을 지우고 새로 만든다.
      // 삭제 대상은 체인 소속으로 한정한다 — 팀원이 직접 만든 교통 블록(auto 아님)은
      // 건드리지 않는다(그룹 자산 보호).
      const oldAutoIds = chain.filter((id) => items[id]?.auto);
      if (realIds.length < 2) return;

      setIsGeneratingTransport(true);
      try {
        const segments = [];
        for (let i = 0; i < realIds.length - 1; i++) {
          const info = await fetchTransitInfo(
            items[realIds[i]],
            items[realIds[i + 1]],
          );
          segments.push(info);
        }

        let newItems = { ...items };
        oldAutoIds.forEach((id) => delete newItems[id]);

        const rebuilt = [];
        const createdLocalIds = [];
        realIds.forEach((id, i) => {
          rebuilt.push(id);
          if (i < realIds.length - 1) {
            const info = segments[i];
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
            };
            rebuilt.push(newId);
            createdLocalIds.push(newId);
          }
        });

        const { newItems: resolvedItems, newChain } = resolveOverlaps(
          newItems,
          rebuilt,
          dayStart[dayKey] ?? 540,
          null,
        );

        // 낙관 적용
        setItems(resolvedItems);
        setChains((prev) => ({ ...prev, [dayKey]: newChain }));

        // ── 서버 반영 (5.5단계): 기존 생성분 삭제 → 밀린 실블록 시각 저장 →
        //    새 교통 블록 생성 → 로컬 임시 id 를 서버 blockId 로 교체 ──
        try {
          await Promise.all(
            oldAutoIds
              .filter(isServerBlock)
              .map((id) => blockApi.deleteBlock(id)),
          );
          await persistShiftedTimes(newChain, items, resolvedItems, null);

          const idMap = {};
          for (const localId of createdLocalIds) {
            const b = resolvedItems[localId];
            const pos = newChain.indexOf(localId);
            // 각 교통 블록의 경계는 양옆 실블록 — 아직 로컬인 다른 교통 블록은
            // neighborKeysAround 가 건너뛴다
            const [before, after] = neighborKeysAround(
              newChain,
              pos,
              resolvedItems,
            );
            const orderKey = generateKeyBetween(before, after);
            const created = await blockApi.createBlock(projectId, {
              ...b,
              endMins: b.startMins + b.dur,
              dayNo: dayNoOf(dayKey),
              orderKey,
              transportMeta: { generated: true, mode: b.sub },
            });
            idMap[localId] = { blockId: created.blockId, orderKey };
          }

          setItems((prev) => {
            const next = { ...prev };
            for (const [localId, mapped] of Object.entries(idMap)) {
              if (!next[localId]) continue;
              next[mapped.blockId] = {
                ...next[localId],
                id: mapped.blockId,
                dayNo: dayNoOf(dayKey),
                orderKey: mapped.orderKey,
                transportMeta: { generated: true, mode: next[localId].sub },
              };
              delete next[localId];
            }
            return next;
          });
          setChains((prev) => ({
            ...prev,
            [dayKey]: (prev[dayKey] || []).map(
              (id) => idMap[id]?.blockId ?? id,
            ),
          }));
        } catch (e) {
          rollbackToServer(e);
        }
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [chains, items, fetchTransitInfo, dayStart, projectId, rollbackToServer],
  );

  const handleAddSingleTransport = useCallback(
    async (dayKey, currentId, nextId) => {
      if (isGeneratingTransport) return;

      const currentChain = [...(chains[dayKey] || [])];
      const insertIdx = currentChain.indexOf(currentId);
      if (insertIdx === -1) return; // 체인에 없는 블록 뒤에는 만들 수 없다

      setIsGeneratingTransport(true);
      try {
        const info = await fetchTransitInfo(items[currentId], items[nextId]);
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
        };
        currentChain.splice(insertIdx + 1, 0, newId);

        const { newItems: resolvedItems, newChain } = resolveOverlaps(
          newItems,
          currentChain,
          dayStart[dayKey] ?? 540,
          null,
        );

        // 낙관 적용
        setItems(resolvedItems);
        setChains((prev) => ({ ...prev, [dayKey]: newChain }));

        // ── 서버 반영 (5.5단계): 밀린 이웃 시각 저장 → 생성 → id 교체 ──
        try {
          await persistShiftedTimes(newChain, items, resolvedItems, null);

          const b = resolvedItems[newId];
          const pos = newChain.indexOf(newId);
          const [before, after] = neighborKeysAround(
            newChain,
            pos,
            resolvedItems,
          );
          const orderKey = generateKeyBetween(before, after);
          const created = await blockApi.createBlock(projectId, {
            ...b,
            endMins: b.startMins + b.dur,
            dayNo: dayNoOf(dayKey),
            orderKey,
            transportMeta: { generated: true, mode: b.sub },
          });

          setItems((prev) => {
            if (!prev[newId]) return prev;
            const next = { ...prev };
            next[created.blockId] = {
              ...next[newId],
              id: created.blockId,
              dayNo: dayNoOf(dayKey),
              orderKey,
              transportMeta: { generated: true, mode: next[newId].sub },
            };
            delete next[newId];
            return next;
          });
          setChains((prev) => ({
            ...prev,
            [dayKey]: (prev[dayKey] || []).map((id) =>
              id === newId ? created.blockId : id,
            ),
          }));
        } catch (e) {
          rollbackToServer(e);
        }
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [
      isGeneratingTransport,
      items,
      chains,
      dayStart,
      fetchTransitInfo,
      projectId,
      rollbackToServer,
    ],
  );

  const timelineDOMRef = useRef(null);
  const poolDOMRef = useRef(null);
  const trashDOMRef = useRef(null);
  const activeDragRef = useRef(null);
  const dragRegionRef = useRef(null);

  // 리사이즈 종료(전역 click) 시 최신 items 를 읽기 위한 latest-ref —
  // 리사이즈 effect 는 items 를 의존성에 두지 않아 클로저가 stale 하다.
  const itemsRef = useRef(items);
  useEffect(() => {
    itemsRef.current = items;
  });

  const handleSaveBlock = async (form) => {
    const targetId = editingBlockId;
    const base = items[targetId];
    if (!base) return;

    // 폼(서버 필드명) → 화면 블록 필드.
    // cat 은 어댑터 매핑으로 되돌린다 — toLowerCase 는 TRANSPORT→"transport" 가
    // 되어 화면의 "trans" 와 어긋난다(카테고리 왕복 파괴 버그의 원인이었다).
    const merged = {
      ...base,
      name: form.name,
      cat: blockApi.CAT_FROM_SERVER[form.category] ?? base.cat,
      sub: form.subCategory,
      address: form.address,
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
      const orderKey = generateKeyBetween(null, firstKey);

      try {
        const created = await blockApi.createBlock(projectId, {
          ...merged,
          dayNo: null, // 커스텀 블록은 후보(POOL)로 생성된다
          orderKey,
        });
        // 세부 내용(detail)은 생성 바디에 없다(명세) — 생성 직후 필드 갱신으로 저장
        if (merged.detail) {
          await blockApi.updateBlockFields(created.blockId, {
            detail: merged.detail,
          });
        }

        const saved = { ...merged, id: created.blockId, dayNo: null, orderKey };
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

    // ── 기존 블록: 변경 필드만 PATCH /fields 배치로 저장한다 (3단계) ──
    // 안 바뀐 필드는 보내지 않는다 — 서버가 필드 화이트리스트(BLOCK400_2)와
    // 필드 단위 LWW 로 판정하므로, diff 가 곧 요청 바디다.
    const changed = {};
    for (const [local, server] of [
      ["name", "name"],
      ["sub", "subCategory"],
      ["address", "address"],
      ["detail", "detail"],
      ["dur", "durationMin"],
      ["cost", "budget"],
    ]) {
      if (merged[local] !== base[local]) changed[server] = merged[local];
    }
    // category 는 보내지 않는다 — 서버 LWW 화이트리스트에 없어 BLOCK400_2 로
    // 배치 전체가 거부된다(2026-07-31 실측). 기존 블록의 카테고리는 폼에서 잠근다.

    // 소요시간이 바뀌면 종료 시각도 함께 맞춘다 — ERD 불변식:
    // 시각이 둘 다 있으면 end_time − start_time == duration_min
    if (changed.durationMin != null && base.startMins != null) {
      changed.endTime = blockApi.minsToTime(base.startMins + merged.dur);
    }

    if (Object.keys(changed).length === 0) {
      setEditingBlockId(null); // 변경 없음 — 요청을 보내지 않는다
      return;
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

      // 로컬 반영. 체인 블록이면 겹침 해소로 밀린 이웃들의 시각도 서버에 저장한다 —
      // 로컬만 밀면 새로고침 때 이웃들이 옛 시각으로 되돌아간다(명세 320행의
      // "이동 후 시각 재계산은 클라이언트 몫" 규칙과 같은 경로, 5단계에서 재사용).
      const updatedItems = { ...items, [targetId]: merged };
      if (chains[activeDay]?.includes(targetId)) {
        const { newItems, newChain } = resolveOverlaps(
          updatedItems,
          chains[activeDay],
          dayStart[activeDay] ?? 540,
          targetId,
        );

        await persistShiftedTimes(chains[activeDay], items, newItems, targetId);

        setItems(newItems);
        setChains((pc) => ({ ...pc, [activeDay]: newChain }));
      } else {
        setItems(updatedItems);
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
      endMins: null,
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

    setChains((prev) => {
      const next = { ...prev };
      Object.keys(next).forEach((day) => {
        next[day] = next[day].filter((x) => x !== id);
      });
      return next;
    });
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

  const { setNodeRef: setTrashDroppable } = useDroppable({ id: "trashArea" });
  const setTrashRef = useCallback(
    (node) => {
      trashDOMRef.current = node;
      setTrashDroppable(node);
    },
    [setTrashDroppable],
  );

  const handleResizeStart = useCallback(
    (id, direction, startY, startDur, originalStartMins, boundTop) => {
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
      const dirty = (chains[activeDay] ?? []).filter(
        (id) =>
          isServerBlock(id) &&
          current[id] &&
          original[id] &&
          (current[id].startMins !== original[id].startMins ||
            current[id].dur !== original[id].dur),
      );
      if (dirty.length === 0) return;

      try {
        await Promise.all(
          dirty.map((id) => {
            const b = current[id];
            const fields = {
              startTime: blockApi.minsToTime(b.startMins),
              endTime: blockApi.minsToTime(b.startMins + b.dur),
            };
            if (b.dur !== original[id].dur) fields.durationMin = b.dur;
            return blockApi.updateBlockFields(id, fields);
          }),
        );
      } catch (e) {
        showToast(
          e?.message ?? "크기 변경을 저장하지 못했어요. 서버 상태로 되돌립니다.",
        );
        reload();
      }
    },
    [chains, activeDay, reload, showToast],
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
        const { newItems } = resolveOverlaps(
          updatedSnapshot,
          chains[activeDay],
          dayStart[activeDay] ?? 540,
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
  }, [resizingState, activeDay, chains, dayStart, persistResize]);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  const handleStartChange = (delta) => {
    setDayStart((prev) => ({
      ...prev,
      [activeDay]: Math.max(
        300,
        Math.min(1380, (prev[activeDay] ?? 540) + delta),
      ),
    }));
  };

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
      const trashRect = trashDOMRef.current?.getBoundingClientRect();

      const isOverTrash =
        !!trashRect &&
        centerX >= trashRect.left &&
        centerX <= trashRect.right &&
        centerY >= trashRect.top &&
        centerY <= trashRect.bottom;
      const isOverPool =
        !isOverTrash &&
        !!poolRect &&
        centerX >= poolRect.left &&
        centerX <= poolRect.right &&
        centerY >= poolRect.top &&
        centerY <= poolRect.bottom;
      const isOverTimeline =
        !isOverTrash &&
        !isOverPool &&
        !!tlRect &&
        centerX >= tlRect.left &&
        centerX <= tlRect.right &&
        centerY >= tlRect.top &&
        centerY <= tlRect.bottom;

      if (isOverTrash) return { region: "trash" };
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
        // 검색 결과는 타임라인에 직접 놓을 수 없다 — 후보(POOL)에 먼저 담는 흐름만
        // 허용한다. 여기서 끊으면 드롭뿐 아니라 미리보기·하이라이트도 함께 꺼진다.
        if (active.data?.current?.from === "search") return { region: null };

        const relativeY =
          topY - tlRect.top + (timelineDOMRef.current?.scrollTop || 0);
        const calcMins =
          (dayStart[activeDay] ?? 540) +
          Math.round((relativeY - TL_PAD_TOP) / PX);
        let dropMins = Math.round(calcMins / SNAP) * SNAP;
        const dur = items[activeIdLocal]?.dur || 60; // 기본 소요시간 60분
        dropMins = Math.max(
          dayStart[activeDay] ?? 540,
          Math.min(dropMins, DAY_END - dur),
        );
        return { region: "timeline", dropMins, dur };
      }
      return { region: null };
    },
    [pool, activeDay, items, dayStart],
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
    const target = computeDropTarget(active);

    setActiveId(null);
    setActiveDragMeta(null);
    activeDragRef.current = null;
    setDragPreview(null);

    if (!target || !target.region) return;

    // 💡 1. 검색 결과 항목을 드래그해서 놓았을 때의 처리 — 드롭 = 블록 생성(POST).
    // 타임라인 직행은 computeDropTarget 이 region: null 로 끊는다 — 검색 블록은
    // 후보(POOL)에 먼저 담는 흐름만 허용한다.
    if (isFromSearch) {
      if (target.region !== "pool") return;

      const place = active.data.current.place;
      const newId = `search-${place.id}-${Date.now()}`;

      // 검색 데이터를 우리 앱의 블록 데이터 구조로 변환.
      // 카카오 응답은 y=위도, x=경도(문자열) — 좌표·placeId 를 버리면 장소성
      // 블록의 서버 검증(BLOCK400)에 걸리고 지도 핀도 찍을 수 없다.
      const newBlock = {
        id: newId,
        cat: catFromKakaoGroup(place.category_group_code),
        sub: place.category_group_name || "검색된 장소",
        name: place.place_name,
        address: place.road_address_name || place.address_name,
        detail: place.phone || "",
        dur: 60, // 기본 소요시간 1시간
        startMins: null, // 후보(POOL) 블록은 시각 없는 느슨한 블록
        endMins: null,
        cost: 0,
        lat: Number(place.y),
        lng: Number(place.x),
        placeId: String(place.id),
        source: "KAKAO",
        auto: false,
      };

      const insertAt = Math.max(0, Math.min(target.insertIndex, pool.length));
      const nextPool = [...pool];
      nextPool.splice(insertAt, 0, newId);

      // 낙관 적용
      setItems((prev) => ({ ...prev, [newId]: newBlock }));
      setPool(nextPool);

      (async () => {
        try {
          const [before, after] = neighborKeysAround(nextPool, insertAt, items);
          const orderKey = generateKeyBetween(before, after);
          const created = await blockApi.createBlock(projectId, {
            ...newBlock,
            dayNo: null,
            orderKey,
          });
          // 전화번호(detail)는 생성 바디에 없다(명세) — 생성 직후 별도 저장
          if (newBlock.detail) {
            await blockApi.updateBlockFields(created.blockId, {
              detail: newBlock.detail,
            });
          }
          adoptServerId(newId, created.blockId, { dayNo: null, orderKey });
        } catch (e) {
          rollbackToServer(e);
        }
      })();
      return;
    }

    // 기존 풀/타임라인 내의 이동 처리 로직 유지
    if (target.region === "trash") {
      // async 삭제(서버 왕복 포함)는 별도 함수로 — 드래그 핸들러는 동기로 끝낸다
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

      // 낙관 적용 — 체인 제거는 pool→pool 이동에서는 자연히 no-op 이다
      setChains((prev) => {
        const next = { ...prev };
        Object.keys(next).forEach((day) => {
          next[day] = next[day].filter((id) => id !== activeIdLocal);
        });
        return next;
      });
      setPool(nextPool);

      // 서버 저장: 후보로 이동/재정렬 = dayNo null + 이웃 사이 orderKey.
      // 시각(startTime/endTime)은 건드리지 않는다 — 후보 블록의 옛 시각은 무해하고,
      // 체인에 다시 올라갈 때 드롭 위치로 재계산된다.
      if (isServerBlock(activeIdLocal)) {
        (async () => {
          try {
            const [before, after] = neighborKeysAround(nextPool, insertAt, items);
            const orderKey = generateKeyBetween(before, after);
            await blockApi.moveBlock(activeIdLocal, { dayNo: null, orderKey });
            // 다음 이동의 이웃 계산이 정확하도록 로컬에도 새 위치 값을 반영
            setItems((prev) =>
              prev[activeIdLocal]
                ? {
                    ...prev,
                    [activeIdLocal]: {
                      ...prev[activeIdLocal],
                      dayNo: null,
                      orderKey,
                    },
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
      const currentDayList = [...(chains[activeDay] || [])];
      if (!currentDayList.includes(activeIdLocal))
        currentDayList.push(activeIdLocal);
      const { newItems, newChain } = resolveOverlaps(
        updatedItems,
        currentDayList,
        dayStart[activeDay] ?? 540,
        activeIdLocal,
      );

      // 낙관 적용
      setItems(newItems);
      setChains((prevChains) => {
        const next = { ...prevChains };
        Object.keys(next).forEach((day) => {
          if (day !== activeDay)
            next[day] = next[day].filter((id) => id !== activeIdLocal);
        });
        next[activeDay] = newChain;
        return next;
      });
      if (isFromPool)
        setPool((prev) => prev.filter((id) => id !== activeIdLocal));

      // 서버 저장: 옮긴 블록 1건의 position(dayNo·orderKey) + 자기 시각 +
      // 겹침 해소로 밀린 이웃들의 시각. resolveOverlaps 는 이동 블록 외의
      // 상대 순서를 보존하므로 position 은 정확히 1건이다(명세와 일치).
      if (isServerBlock(activeIdLocal)) {
        (async () => {
          try {
            const pos = newChain.indexOf(activeIdLocal);
            const [before, after] = neighborKeysAround(newChain, pos, newItems);
            const orderKey = generateKeyBetween(before, after);
            const moved = newItems[activeIdLocal];

            await blockApi.moveBlock(activeIdLocal, {
              dayNo: dayNoOf(activeDay),
              orderKey,
            });
            await Promise.all([
              blockApi.updateBlockFields(activeIdLocal, {
                startTime: blockApi.minsToTime(moved.startMins),
                endTime: blockApi.minsToTime(moved.startMins + moved.dur),
              }),
              persistShiftedTimes(newChain, items, newItems, activeIdLocal),
            ]);

            // 다음 이동의 이웃 계산이 정확하도록 로컬에도 새 위치 값을 반영
            setItems((prev) =>
              prev[activeIdLocal]
                ? {
                    ...prev,
                    [activeIdLocal]: {
                      ...prev[activeIdLocal],
                      dayNo: dayNoOf(activeDay),
                      orderKey,
                    },
                  }
                : prev,
            );
          } catch (e) {
            rollbackToServer(e);
          }
        })();
      }
    }
  };

  // Day 개수가 프로젝트 기간을 따라 바뀌므로, 동기화 effect 가 돌기 전 한 프레임 동안
  // 아직 없는 Day 를 가리킬 수 있다 — 기본 09:00 으로 받쳐 NaN 좌표를 만들지 않는다.
  const timelineStart = dayStart[activeDay] ?? 540;
  const timelineEnd = DAY_END;
  const timeSlots = [];
  for (let t = timelineStart; t <= timelineEnd; t += 30) timeSlots.push(t);

  // 💡 드래그 중인 임시 아이템 정의 (검색 패널에서 드래그할 경우 임시 객체를 만들어 보여줌)
  let draggedItem = null;
  if (activeId) {
    if (activeDragMeta?.from === "search") {
      const place = activeDragMeta.place;
      draggedItem = {
        id: activeId,
        cat: catFromKakaoGroup(place.category_group_code),
        name: place.place_name,
        sub: place.category_group_name,
        address: place.road_address_name || place.address_name,
        dur: 60,
        cost: 0,
      };
    } else {
      draggedItem = items[activeId];
    }
  }

  const isDraggingFromPool = activeId ? pool.includes(activeId) : false;
  const isDraggingFromSearch = activeDragMeta?.from === "search";

  let displayItems = items;
  let displayChain = chains[activeDay] || [];

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
    const tempChain = [...displayChain];
    if (!tempChain.includes(activeId)) tempChain.push(activeId);
    const { newItems, newChain } = resolveOverlaps(
      tempItems,
      tempChain,
      timelineStart,
      activeId,
    );
    displayItems = newItems;
    displayChain = newChain;
  }

  const activeDayItems = displayChain
    .filter((id) => !(isDraggingFromPool && id === activeId) && id !== activeId)
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
    .filter(Boolean);

  // 스냅샷이 시드되기 전(로딩 중)에는 보드를 그리지 않는다.
  // 에러일 때는 위 effect 가 그룹 페이지로 되돌린다.
  // dayStart 는 조건에 넣지 않는다 — 기간 미정 프로젝트는 dayKeys(기본 4일)가
  // 서버 chains 보다 넓어 시드에 없는 Day 가 있을 수 있고, 그때는 `?? 540` 폴백이 받는다.
  if (status !== "loaded") return null;

  // 💡 타임라인 드래그 미리보기 합치기
  if (dragPreview?.region === "timeline" && activeId && draggedItem) {
    activeDayItems.push({
      id: activeId,
      item: displayItems[activeId],
      startMins: displayItems[activeId].startMins,
      endMins: displayItems[activeId].startMins + displayItems[activeId].dur,
    });
    activeDayItems.sort((a, b) => a.startMins - b.startMins);
  }


  return (
    <>
      {/* 개인 페이지 › 그룹명 › 프로젝트명. 그룹·프로젝트를 못 불러온 동안에는
          자리만 지키는 문구를 쓴다(빈 칸이 생기면 경로가 끊겨 보인다). */}
      <AppBar
        crumbs={[
          { label: "개인 페이지", to: "/my" },
          { label: group?.name ?? "그룹", to: `/groups/${groupId}` },
          { label: project?.name ?? "여행 대시보드" },
        ]}
        members={group?.members ?? []}
        // 실시간 접속 정보가 아직 없어 지금은 나만 활동 중으로 표시된다.
        // WebSocket 이 붙으면 접속자 id 목록을 그대로 여기에 넣으면 된다.
        activeMemberIds={currentUser?.id ? [currentUser.id] : []}
      />

      {/* 모드 전환 바 + 보드를 한 껍데기(.dash-shell) 안에 두어 경계 없이 이어 보이게 한다.
          예전에는 전환 바가 자기 배경을 가진 별도 띠였다. */}
      <div className="dash-shell">
        <div className="dash-toolbar">
          <div className="dash-heading">
            <h1>{project?.name ?? "여행 대시보드"}</h1>
            <span className="dash-sub">
              {[
                project?.destination,
                // 시작일 ~ 종료일 (기간이 하루면 물결표 없이 한 날짜만)
                project?.startDate && project?.endDate
                  ? dayKeys.length > 1
                    ? `${dayDate(project, 0, "short")} ~ ${dayDate(project, dayKeys.length - 1, "short")}`
                    : dayDate(project, 0, "short")
                  : null,
                `${dayKeys.length}일`,
              ]
                .filter(Boolean)
                .join(" · ")}
            </span>
          </div>
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
        </div>

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
            <div className="dashboard-page dash-body">
              <div className="daycol">
                {dayKeys.map((day, i) => (
                  <DayTab
                    key={day}
                    label={`Day ${i + 1}`}
                    date={dayDate(project, i, "short")}
                    count={(chains[day] || []).length}
                    isActive={activeDay === day}
                    onClick={() => setActiveDay(day)}
                  />
                ))}
              </div>

              <div className="main">
                <div className="board plan-board">
                  <div className="bd-head">
                    <h2>Day {activeDayIndex + 1}</h2>
                    {/* 날짜를 누르면 캘린더가 열리고, 고른 날짜에 이 Day 가 오도록
                        여행 일정 전체가 함께 옮겨진다. 프로젝트를 못 불러왔으면 표시만. */}
                    <div className="date-wrap">
                      {project ? (
                        <button
                          type="button"
                          className="date date-btn"
                          title="날짜를 눌러 일정을 옮기세요"
                          onClick={() => setDateEditOpen((v) => !v)}
                        >
                          {dayDate(project, activeDayIndex) || "날짜 미정"}
                          <span className="date-ico">🗓</span>
                        </button>
                      ) : (
                        <span className="date">
                          {dayDate(project, activeDayIndex) || "날짜 미정"}
                        </span>
                      )}

                      {dateEditOpen && (
                        <>
                          <div
                            className="date-pop-back"
                            onClick={() => setDateEditOpen(false)}
                          />
                          <div className="date-pop">
                            <label htmlFor="day-date-input">
                              Day {activeDayIndex + 1} 날짜
                            </label>
                            <input
                              id="day-date-input"
                              type="date"
                              value={dayISODate(project, activeDayIndex)}
                              onChange={(e) =>
                                handleDayDateChange(e.target.value)
                              }
                            />
                            <p>
                              고른 날짜에 Day {activeDayIndex + 1} 이 오도록 여행{" "}
                              {dayKeys.length}일 전체가 함께 옮겨져요.
                            </p>
                          </div>
                        </>
                      )}
                    </div>
                    <div className="right">
                      <button
                        className="auto-transport-btn"
                        onClick={() => regenerateAutoTransport(activeDay)}
                        disabled={
                          isGeneratingTransport ||
                          (chains[activeDay] || []).filter(
                            (id) => !items[id]?.auto,
                          ).length < 2
                        }
                      >
                        {isGeneratingTransport
                          ? "생성 중..."
                          : "🚗 이동수단 자동 생성"}
                      </button>
                      <div className="start-ctl">
                        시작{" "}
                        <button onClick={() => handleStartChange(-30)}>−</button>
                        <b>{fmtTime(timelineStart)}</b>
                        <button onClick={() => handleStartChange(30)}>＋</button>
                      </div>
                    </div>
                  </div>

                  <div
                    className={`tl ${dragPreview?.region === "timeline" ? "dropover" : ""}`}
                    ref={setTimelineRefs}
                    onScroll={() => {
                      if (activeDragRef.current)
                        setDragPreview(computeDropTarget(activeDragRef.current));
                    }}
                    // 하루 길이(분 × PX)만 인라인으로 넘긴다 — 나머지 모양은 CSS(.tl)
                    style={{
                      height: `${(timelineEnd - timelineStart) * PX + 120}px`,
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
                          <span className="tl-mark-time">{fmtTime(t)}</span>
                          <div className="tl-mark-line" />
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
                      {activeDayItems.map((data, index) => {
                        const boundTop =
                          index > 0
                            ? activeDayItems[index - 1].endMins
                            : timelineStart;

                        const nextData = activeDayItems[index + 1];
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
                                  dayStartMins={timelineStart}
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
                                dayStartMins={timelineStart}
                                boundTop={boundTop}
                                onEditBlock={setEditingBlockId}
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
                                    handleAddSingleTransport(
                                      activeDay,
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

                      {activeDayItems.length === 0 && (
                        <div className="endzone">
                          ＋ 비어있는 타임라인의 원하는 시간 위치로 드래그하여
                          일정을 추가하세요
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                <div className="pool-row">
                  <div
                    className={`pool-sec ${dragPreview?.region === "pool" ? "dropover" : ""}`}
                    ref={setPoolRef}
                  >
                    <div className="pool-head">
                      <div>
                        <b>후보 목록</b> <span className="n">{pool.length}</span>
                        <span className="pool-hint">
                          자유롭게 끌어다 놓고 빼세요
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
                      <SortableContext
                        items={pool}
                        strategy={rectSortingStrategy}
                      >
                        {pool.map((id) => (
                          <PoolCard
                            key={id}
                            id={id}
                            item={items[id]}
                            onEditBlock={setEditingBlockId}
                          />
                        ))}
                      </SortableContext>
                      {dragPreview?.region === "pool" && !isDraggingFromPool && (
                        <div className="pool-dropzone" />
                      )}
                    </div>
                  </div>

                  <div
                    ref={setTrashRef}
                    className={`trash-zone ${activeId ? "dragging" : ""} ${dragPreview?.region === "trash" ? "dropover" : ""}`}
                  >
                    {/* 개인 페이지의 삭제 버튼과 같은 휴지통 글리프(🗑)를 쓴다 */}
                    <span className="trash-ico">🗑</span>
                    <span className="trash-text">
                      여기로 블럭을
                      <br />
                      끌어다 놓으면
                      <br />
                      삭제됩니다
                    </span>
                  </div>
                </div>
              </div>

              <div className="side">
                <div className="panel">
                  <div className="bud-total">
                    <span className="bud-total-label">총 </span>
                    <span className="bud-total-value">
                      {totalBudget.toLocaleString()}원
                    </span>
                  </div>

                  <div className="bud-target">
                    <span>희망 총 예산</span>
                    <div className="bud-stepper">
                      <button onClick={() => handleTargetBudgetChange(-100000)}>
                        -
                      </button>
                      <span className="bud-stepper-value">
                        {targetBudget.toLocaleString()}원
                      </span>
                      <button onClick={() => handleTargetBudgetChange(100000)}>
                        +
                      </button>
                    </div>
                  </div>

                  {/* 블록별 사용량 — 한 덩어리 갈색 바 대신 블록마다 색이 다른 칸으로 쌓는다.
                      칸에 마우스를 올리면 블록 이름·금액·비중이 툴팁으로 나온다. */}
                  <div className="bud-track">
                    {budgetSegments.map((seg) => (
                      <div
                        key={seg.cat}
                        className="bud-seg"
                        style={{
                          width: `${seg.percent}%`,
                          backgroundColor: seg.color,
                        }}
                        title={`${seg.name} · ${seg.cost.toLocaleString()}원 (사용액의 ${Math.round(seg.shareOfTotal)}%)`}
                      />
                    ))}
                    {budgetSegments.length === 0 && (
                      <div className="bud-empty">아직 비용이 있는 블록이 없어요</div>
                    )}
                  </div>

                  {budgetSegments.length > 0 && (
                    <div className="bud-legend">
                      {budgetSegments.map((seg) => (
                        <span key={seg.cat} className="bud-legend-item">
                          <i style={{ backgroundColor: seg.color }} />
                          <b>{seg.name}</b>
                          {seg.cost.toLocaleString()}원
                          <em>{Math.round(seg.shareOfTotal)}%</em>
                        </span>
                      ))}
                    </div>
                  )}

                  <div className="bud-foot">
                    <span>희망 예산의 {Math.round(budgetPercent)}% 사용</span>
                    <span
                      className={`bud-left ${remainingBudget < 0 ? "is-over" : ""}`}
                    >
                      {remainingBudget < 0
                        ? `${Math.abs(remainingBudget).toLocaleString()}원 초과`
                        : `남은 ${remainingBudget.toLocaleString()}원`}
                    </span>
                  </div>
                </div>

                <div className="panel">
                  <h4 className="panel-title">
                    지도{" "}
                    <span className="panel-title-sub">
                      검색하면 지도가 이동합니다
                    </span>
                  </h4>
                  {/* 높이는 CSS(.map-box)에서 화면 높이에 맞춰 늘린다 — 사이드 폭이
                      넓어진 만큼 지도도 남는 공간을 다 쓰게 하기 위함.
                      초기화는 ref callback 으로 — getElementById 방식은 로딩 가드가
                      null 을 반환하는 동안 컨테이너가 없어 재진입 시 회색 지도가 됐다 */}
                  <div
                    id="kakao-map-container"
                    className="map-box"
                    ref={initMapOnContainer}
                  />
                </div>

                <div className="panel">
                  <h4 className="panel-title">카카오 장소 검색</h4>
                  <div className="search-box">
                    <form className="search-form" onSubmit={handleSearchPlace}>
                      <input
                        type="text"
                        value={searchKeyword}
                        onChange={(e) => setSearchKeyword(e.target.value)}
                        placeholder="도시, 명소, 음식..."
                      />
                      <button type="submit">검색</button>
                    </form>

                    {/* 💡 검색 결과 리스트: 버튼이 사라지고 이젠 꾹 눌러서 끌 수 있습니다! */}
                    <div className="search-results" ref={searchListRef}>
                      {searchResults.map((place) => (
                        <SearchResultDraggable
                          key={place.id}
                          place={place}
                          // 클릭 = 지도 이동 + 상세 말풍선 (드래그와 별개 동작)
                          onClick={handlePlaceClick}
                        />
                      ))}
                      {searchResults.length === 0 && (
                        <div className="search-empty">
                          검색 결과가 여기에 표시됩니다.
                          <br />
                          검색 후 패널을 왼쪽으로 끌어다 놓으세요.
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </div>

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
            </div>
          </DndContext>
        ) : (
          <ReadModeView
            chains={chains}
            items={items}
            dayKeys={dayKeys}
            project={project}
          />
        )}
      </div>

      {editingBlockId && items[editingBlockId] && (
        <div className="blk-modal-ov">
          {(() => {
            const item = items[editingBlockId];
            const sMins = item.startMins;
            const eMins = sMins + item.dur;
            const dayNum = activeDay.replace("d", "");
            // 후보(POOL) 블록은 시각이 없다(느슨한 블록) — 폼이 "시간 정보 없음"을 띄운다
            const timeStr =
              sMins == null
                ? ""
                : `Day ${dayNum} · ${fmtTime(sMins)} - ${fmtTime(eMins)}`;

            return (
              <BlockEditForm
                initialData={item}
                timeString={timeStr}
                // 서버가 category 필드 갱신을 지원하지 않는다(BLOCK400_2) —
                // 카테고리는 생성 시에만 정할 수 있다
                categoryLocked={!isTempId(editingBlockId)}
                onSave={handleSaveBlock}
                onCancel={handleCancelEdit}
              />
            );
          })()}
        </div>
      )}

      {viewMode === "edit" && <ChatbotWidget />}
    </>
  );
}
