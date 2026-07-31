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
import { AppBar } from "../My/shared/ui/AppBar";
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

const CAT_COLORS = {
  stay: { nm: "숙소", hex: "#8a5aa8", bg: "var(--stayB, #f3edfa)" },
  food: { nm: "식당", hex: "#d97e3c", bg: "var(--foodB, #fdf1e4)" },
  spot: { nm: "명소/활동", hex: "#3e8e63", bg: "var(--spotB, #eaf5ec)" },
  etc: { nm: "기타", hex: "#7a6a5c", bg: "var(--etcB, #f1ece4)" },
  trans: { nm: "교통", hex: "#6b7fc7", bg: "var(--transB, #eef0fb)" },
};

const fmtTime = (mins) => {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
};

const won = (n) => (n ? n.toLocaleString("ko-KR") + "원" : "무료");
const catOf = (item) => CAT_COLORS[item?.cat] || CAT_COLORS.etc;

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

function CardBody({
  id,
  item,
  mode,
  startMins,
  endMins,
  isThisResizing,
  onEdge,
  onEditBlock,
}) {
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
        <div className="nm" style={{ fontWeight: "bold", color: "#333" }}>
          {item?.name}
        </div>
        <div className="sub">{item?.memo || item?.addr}</div>
      </>
    );
  }

  return (
    <>
      {onEdge && (
        <div
          onClick={(e) => onEdge(e, "top")}
          style={{
            position: "absolute",
            top: 0,
            left: 0,
            right: 0,
            height: "16px",
            cursor: "ns-resize",
            zIndex: 40,
            background:
              isThisResizing === "top" ? "rgba(0,0,0,0.1)" : "transparent",
          }}
        />
      )}
      <div className="l1">
        <span className="cat">
          {catStyle.nm}
          {item?.sub ? ` · ${item.sub}` : ""}
        </span>
        {item?.auto && <span className="auto-badge">자동</span>}
        <span>
          <span className="nm" style={{ fontWeight: "bold", color: "#333" }}>
            {item?.name}
          </span>{" "}
          <span className="nm-sub">{item?.memo}</span>
        </span>
        <span className="time">
          {fmtTime(startMins)} – {fmtTime(endMins)}
        </span>
        <span className="cost">{won(item?.cost)}</span>
      </div>
      <div className="addr">📍 {item?.addr || "위치 정보 없음"}</div>
      <div className="ctl" style={{ marginTop: "auto", paddingTop: "8px" }}>
        <span
          className="dur"
          style={{
            color: isThisResizing ? catStyle.hex : undefined,
            fontWeight: isThisResizing ? "bold" : "normal",
          }}
        >
          {isThisResizing
            ? "마우스를 움직여 조절 후 클릭하여 확정"
            : `소요 ${item?.dur}분`}
        </span>
      </div>
      {onEdge && (
        <div
          onClick={(e) => onEdge(e, "bottom")}
          style={{
            position: "absolute",
            bottom: 0,
            left: 0,
            right: 0,
            height: "16px",
            cursor: "ns-resize",
            zIndex: 40,
            background:
              isThisResizing === "bottom" ? "rgba(0,0,0,0.1)" : "transparent",
          }}
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
  const style = {
    transform: isDragging ? undefined : CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.35 : 1,
    "--dc": catStyle.hex,
    "--cb": catStyle.bg,
    cursor: "pointer", // 💡 박스 전체에 클릭(손가락) 커서 적용
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className="pcard"
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
  const { attributes, listeners, setNodeRef, transform, isDragging } =
    useDraggable({ id, data: { from: "timeline" } });
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

  const topPx = (startMins - dayStartMins) * PX;
  const slotStyle = {
    position: "absolute",
    top: `${topPx}px`,
    left: "10px",
    right: "10px",
    height: `${height}px`,
    opacity: isDragging ? 0 : 1,
    pointerEvents: isDragging ? "none" : "auto",
    transition:
      isDragging || isThisResizing
        ? "none"
        : "top 0.25s cubic-bezier(0.2, 0, 0, 1), height 0.25s cubic-bezier(0.2, 0, 0, 1)",
    zIndex: isDragging || isThisResizing ? 100 : 5,
  };
  const cardStyle = {
    height: "100%",
    "--dc": catStyle.hex,
    "--cb": catStyle.bg,
    position: "relative",
    overflow: "hidden",
    cursor: isThisResizing ? "ns-resize" : "pointer", // 💡 리사이징 중이 아닐 때는 포인터(손가락) 커서
    boxShadow: isThisResizing ? `0 0 0 2px ${catStyle.hex}` : undefined,
  };

  return (
    <div className="slot" style={slotStyle}>
      <span
        className="tlab"
        style={{
          position: "absolute",
          left: "-64px",
          top: "-10px",
          width: "52px",
          textAlign: "right",
          fontSize: "12.5px",
          fontWeight: "700",
          color: catStyle.hex,
          background: "rgba(255, 253, 248, 0.95)",
          zIndex: 15,
          padding: "2px 0",
        }}
      >
        {fmtTime(startMins)}
      </span>
      <span
        className="dot"
        style={{
          position: "absolute",
          left: "-6px",
          top: "-7px",
          "--dc": catStyle.hex,
          zIndex: 15,
        }}
      />
      <div
        ref={setNodeRef}
        style={cardStyle}
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
function SearchResultDraggable({ place }) {
  const id = `search-result-${place.id}`;
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id,
    data: { from: "search", place },
  });

  return (
    <div
      ref={setNodeRef}
      style={{
        display: "flex",
        justifyContent: "space-between",
        alignItems: "center",
        padding: "10px 12px",
        backgroundColor: "#fff",
        borderRadius: "10px",
        border: "1px solid #e6dec8",
        opacity: isDragging ? 0.4 : 1,
        cursor: "grab",
        boxShadow: isDragging
          ? "0 8px 16px rgba(0,0,0,0.12)"
          : "0 2px 4px rgba(0,0,0,0.02)",
        transition: "box-shadow 0.2s, transform 0.2s",
      }}
      {...attributes}
      {...listeners}
    >
      <div style={{ display: "flex", alignItems: "flex-start", gap: "8px" }}>
        <div style={{ color: "#d97e3c", fontSize: "10px", marginTop: "4px" }}>
          ●
        </div>
        <div>
          <div
            style={{
              fontSize: "14px",
              fontWeight: "bold",
              color: "#333",
              marginBottom: "2px",
            }}
          >
            {place.place_name}
          </div>
          <div style={{ fontSize: "12px", color: "#888", marginBottom: "2px" }}>
            {place.road_address_name || place.address_name}
          </div>
          <div style={{ fontSize: "11px", color: "#aaa" }}>
            {place.category_group_name}
          </div>
        </div>
      </div>
      {/* 끌어다 놓기 유도용 손잡이 아이콘 */}
      <div style={{ color: "#c9b8a5", fontSize: "18px", paddingLeft: "8px" }}>
        ⠿
      </div>
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
          <div
            key={day}
            style={{
              marginBottom: "32px",
              backgroundColor: "#fbf8f1",
              padding: "32px",
              borderRadius: "16px",
              boxShadow: "0 4px 12px rgba(0,0,0,0.05)",
            }}
          >
            {/* 폰트는 앱 공통 손글씨체(--font-app)를 쓴다 — 이전에 serif/sans-serif 를
                직접 박아둬서 이 두 줄만 다른 폰트로 보였다. */}
            <div style={{ marginBottom: "24px" }}>
              <h2
                style={{
                  fontSize: "26px",
                  color: "#3d2b22",
                  margin: "0 0 8px 0",
                  fontFamily: "var(--font-app)",
                }}
              >
                Day {index + 1}{" "}
                <span
                  style={{
                    fontSize: "15px",
                    color: "#888",
                    fontWeight: "normal",
                    fontFamily: "var(--font-app)",
                  }}
                >
                  {dayDate(project, index) || "날짜 미정"}
                </span>
              </h2>
            </div>
            <div
              style={{
                position: "relative",
                display: "flex",
                flexDirection: "column",
                gap: "16px",
              }}
            >
              <div
                style={{
                  position: "absolute",
                  left: "55px",
                  top: "20px",
                  bottom: "20px",
                  width: "2px",
                  backgroundColor: "#e6dec8",
                  zIndex: 0,
                }}
              />
              {chain.map((id) => {
                const item = items[id];
                if (!item) return null;
                const startMins = item.startMins;
                const endMins = startMins + item.dur;
                const catStyle = catOf(item);
                return (
                  <div
                    key={id}
                    style={{
                      display: "flex",
                      alignItems: "center",
                      gap: "20px",
                      zIndex: 1,
                    }}
                  >
                    <div
                      style={{
                        width: "60px",
                        textAlign: "right",
                        fontWeight: "bold",
                        fontSize: "15px",
                        color: catStyle.hex,
                        backgroundColor: "#fbf8f1",
                      }}
                    >
                      {fmtTime(startMins)}
                    </div>
                    <div
                      style={{
                        width: "12px",
                        height: "12px",
                        borderRadius: "50%",
                        backgroundColor: catStyle.hex,
                        border: "2px solid #fbf8f1",
                        flexShrink: 0,
                      }}
                    />
                    <div
                      style={{
                        flex: 1,
                        backgroundColor: catStyle.bg,
                        padding: "18px 24px",
                        borderRadius: "12px",
                        display: "flex",
                        justifyContent: "space-between",
                        alignItems: "center",
                      }}
                    >
                      <div
                        style={{
                          display: "flex",
                          alignItems: "center",
                          gap: "12px",
                        }}
                      >
                        <span
                          style={{
                            backgroundColor: catStyle.hex,
                            color: "#fff",
                            fontSize: "12px",
                            padding: "4px 8px",
                            borderRadius: "6px",
                            fontWeight: "bold",
                          }}
                        >
                          {catStyle.nm} {item.sub ? `· ${item.sub}` : ""}
                        </span>
                        <div>
                          <div
                            style={{
                              fontWeight: "bold",
                              fontSize: "17px",
                              color: "#333",
                              marginBottom: "4px",
                            }}
                          >
                            {item.name}
                          </div>
                          <div style={{ fontSize: "13px", color: "#666" }}>
                            📍 {item.addr || "위치 정보 없음"}
                          </div>
                        </div>
                      </div>
                      <div style={{ textAlign: "right" }}>
                        <div
                          style={{
                            fontWeight: "bold",
                            color: catStyle.hex,
                            marginBottom: "6px",
                          }}
                        >
                          {fmtTime(startMins)} - {fmtTime(endMins)}
                        </div>
                        <div
                          style={{
                            fontSize: "15px",
                            fontWeight: "bold",
                            color: "#333",
                          }}
                        >
                          {won(item.cost)}
                        </div>
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
  const { groupId, projectId } = useParams();

  // 상단바(개인 페이지 › 그룹명 › 프로젝트명)와 Day 탭·날짜가 실제 데이터를 쓰도록
  // 그룹 상세와 프로젝트 목록을 그대로 재사용한다. 그룹 페이지에서 프로젝트를
  // 수정하면 같은 API 를 다시 읽는 이 화면에도 그대로 반영된다.
  const numericGroupId = Number(groupId);
  const { group } = useGroupDetail(numericGroupId);
  // updateProject 까지 받아 이 화면에서도 프로젝트명·기간을 고칠 수 있게 한다.
  // 훅이 목록을 갱신하므로 제목·Day 탭·날짜가 저장 즉시 따라 바뀐다(재조회 없음).
  const { projects, updateProject } = useProjects(numericGroupId);
  const currentUser = useAuthStore((s) => s.currentUser);

  const project =
    projects.find((p) => p.projectId === Number(projectId)) ?? null;

  // 여행 기간이 곧 Day 개수다. 기간이 바뀌면(그룹 페이지에서 수정) 탭도 함께 바뀐다.
  const dayKeys = useMemo(() => dayKeysOf(project), [project]);

  const showToast = useToastStore((s) => s.show);

  const [viewMode, setViewMode] = useState("edit");
  const [dateEditOpen, setDateEditOpen] = useState(false); // 날짜 캘린더 열림 여부
  const [selectedDay, setActiveDay] = useState("d1");
  // 기간이 줄어 보고 있던 Day 가 사라지면 첫째 날을 본다 — 상태를 되돌리지 않고
  // 렌더 시점에 정하므로 "없는 Day 를 가리키는 한 프레임"이 생기지 않는다.
  const activeDay = dayKeys.includes(selectedDay) ? selectedDay : dayKeys[0];
  const activeDayIndex = Math.max(0, dayKeys.indexOf(activeDay));

  const [dayStart, setDayStart] = useState({
    d1: 540,
    d2: 540,
    d3: 540,
    d4: 540,
  });

  const [items, setItems] = useState({
    b1: {
      id: "b1",
      cat: "spot",
      name: "성산일출봉",
      dur: 90,
      startMins: 540,
      cost: 0,
      auto: false,
    },
    b2: {
      id: "b2",
      cat: "food",
      name: "제주 흑돼지",
      dur: 60,
      startMins: 660,
      cost: 50000,
      auto: false,
    },
    b3: {
      id: "b3",
      cat: "stay",
      name: "신라호텔",
      dur: 120,
      startMins: 750,
      cost: 200000,
      auto: false,
    },
    c1: {
      id: "c1",
      cat: "spot",
      name: "우도",
      dur: 120,
      startMins: 540,
      cost: 10000,
      auto: false,
    },
    c2: {
      id: "c2",
      cat: "etc",
      name: "렌트카 대여",
      dur: 30,
      startMins: 540,
      cost: 50000,
      auto: false,
    },
  });

  const [chains, setChains] = useState({
    d1: ["b1", "b2", "b3"],
    d2: [],
    d3: [],
    d4: [],
  });
  const [pool, setPool] = useState(["c1", "c2"]);

  /**
   * 기간이 줄어 사라진 Day 에 남아 있던 블록은 버리지 않고 후보 목록으로 되돌린다 —
   * 서버가 PATCH 응답의 movedToPool 로 알려주는 것과 같은 규칙이다.
   * (늘어난 Day 는 상태를 만들 필요가 없다. 조회하는 쪽이 전부 `chains[day] || []`,
   *  `dayStart[day] ?? 540` 로 비어 있는 경우를 받아낸다.)
   */
  useEffect(() => {
    setChains((prev) => {
      const gone = Object.keys(prev).filter((key) => !dayKeys.includes(key));
      if (gone.length === 0) return prev;

      const dropped = gone.flatMap((key) => prev[key]);
      if (dropped.length > 0) {
        setPool((p) => [...dropped.filter((id) => !p.includes(id)), ...p]);
      }
      const next = {};
      dayKeys.forEach((key) => {
        next[key] = prev[key] ?? [];
      });
      return next;
    });
  }, [dayKeys]);

  const [editingBlockId, setEditingBlockId] = useState(null);
  const [activeId, setActiveId] = useState(null);
  const [resizingState, setResizingState] = useState(null);
  const [dragPreview, setDragPreview] = useState(null);
  const [isGeneratingTransport, setIsGeneratingTransport] = useState(false);

  const [map, setMap] = useState(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [searchResults, setSearchResults] = useState([]);
  const searchListRef = useRef(null);

  useEffect(() => {
    // 지도를 실제로 화면에 그리는 함수
    const initMap = () => {
      window.kakao.maps.load(() => {
        const container = document.getElementById("kakao-map-container");
        if (container) {
          const options = {
            center: new window.kakao.maps.LatLng(33.450701, 126.570667),
            level: 7,
          };
          const newMap = new window.kakao.maps.Map(container, options);
          setMap(newMap);
        }
      });
    };

    // 1. 이미 스크립트가 있다면 바로 지도 그리기 (중복 방지)
    if (document.getElementById("kakao-map-script") && window.kakao) {
      initMap();
      return;
    }

    // 2. 스크립트가 없다면 새로 만들어서 붙이기
    const script = document.createElement("script");
    script.id = "kakao-map-script";
    // 💡 autoload=false 파라미터가 반드시 있어야 리액트와 충돌하지 않습니다!
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=71b94eabee0913242230da390f4d20f2&autoload=false&libraries=services`;
    script.async = true;

    // 3. 스크립트 로딩이 끝나면 지도 그리기 함수 실행
    script.onload = initMap;

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
      await updateProject(project.projectId, {
        name: project.name,
        startDate: startISO,
        endDate: endISO,
      });
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
        setSearchResults(data);

        // 💡 2. 데이터가 업데이트될 때 스크롤을 맨 위로 끌어올림!
        if (searchListRef.current) {
          searchListRef.current.scrollTop = 0;
        }

        if (map) {
          const bounds = new window.kakao.maps.LatLngBounds();
          data.forEach((place) => {
            bounds.extend(new window.kakao.maps.LatLng(place.y, place.x));
            new window.kakao.maps.Marker({
              map: map,
              position: new window.kakao.maps.LatLng(place.y, place.x),
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

  const totalBudget = Object.values(items).reduce(
    (sum, item) => sum + (item.cost || 0),
    0,
  );
  const [targetBudget, setTargetBudget] = useState(1500000);
  const handleTargetBudgetChange = (amount) => {
    setTargetBudget((prev) => Math.max(0, prev + amount));
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

  const fetchTransitInfo = useCallback(async (fromItem, toItem) => {
    await new Promise((resolve) => setTimeout(resolve, 120));
    return { mode: "이동", dur: 20, cost: 0 };
  }, []);

  const regenerateAutoTransport = useCallback(
    async (dayKey) => {
      const realIds = (chains[dayKey] || []).filter((id) => !items[id]?.auto);
      if (realIds.length < 2) return;

      setIsGeneratingTransport(true);
      try {
        const segments = [];
        for (let i = 0; i < realIds.length - 1; i++) {
          const info = await fetchTransitInfo(
            items[realIds[i]],
            items[realIds[i + 1]],
          );
          segments.push({ afterId: realIds[i], index: i, info });
        }

        let newItems = { ...items };
        Object.keys(newItems).forEach((id) => {
          if (newItems[id]?.auto && newItems[id]?.autoDay === dayKey)
            delete newItems[id];
        });

        const rebuilt = [];
        realIds.forEach((id, i) => {
          rebuilt.push(id);
          if (i < realIds.length - 1) {
            const info = segments[i].info;
            const newId = `auto-${dayKey}-${id}-${i}`;
            newItems[newId] = {
              cat: "trans",
              sub: info.mode,
              name: `${newItems[id]?.name || ""} 다음 이동`,
              addr: "",
              dur: info.dur,
              cost: info.cost,
              auto: true,
              autoDay: dayKey,
              startMins: newItems[id].startMins + newItems[id].dur,
            };
            rebuilt.push(newId);
          }
        });

        const { newItems: resolvedItems, newChain } = resolveOverlaps(
          newItems,
          rebuilt,
          dayStart[dayKey],
          null,
        );
        setItems(resolvedItems);
        setChains((prev) => ({ ...prev, [dayKey]: newChain }));
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [chains, items, fetchTransitInfo, dayStart],
  );

  const handleAddSingleTransport = useCallback(
    async (dayKey, currentId, nextId) => {
      if (isGeneratingTransport) return;
      setIsGeneratingTransport(true);
      try {
        const info = await fetchTransitInfo(items[currentId], items[nextId]);
        const newId = `auto-${dayKey}-${currentId}-${Date.now()}`;

        let newItems = { ...items };
        newItems[newId] = {
          cat: "trans",
          sub: info.mode,
          name: `${items[currentId]?.name || "이전 장소"} 다음 이동`,
          addr: "",
          dur: info.dur,
          cost: info.cost,
          auto: true,
          autoDay: dayKey,
          startMins: items[currentId].startMins + items[currentId].dur,
        };

        let currentChain = [...(chains[dayKey] || [])];
        const insertIdx = currentChain.indexOf(currentId);
        if (insertIdx !== -1) {
          currentChain.splice(insertIdx + 1, 0, newId);
        }

        const { newItems: resolvedItems, newChain } = resolveOverlaps(
          newItems,
          currentChain,
          dayStart[dayKey],
          null,
        );

        setItems(resolvedItems);
        setChains((prev) => ({ ...prev, [dayKey]: newChain }));
      } finally {
        setIsGeneratingTransport(false);
      }
    },
    [isGeneratingTransport, items, chains, dayStart, fetchTransitInfo],
  );

  const timelineDOMRef = useRef(null);
  const poolDOMRef = useRef(null);
  const trashDOMRef = useRef(null);
  const activeDragRef = useRef(null);
  const dragRegionRef = useRef(null);

  const handleSaveBlock = (updatedData) => {
    setItems((prev) => {
      const existing = prev[editingBlockId];
      const updatedItems = {
        ...prev,
        [editingBlockId]: {
          ...existing,
          name: updatedData.name,
          cat: updatedData.category
            ? updatedData.category.toLowerCase()
            : existing.cat,
          sub: updatedData.subCategory,
          addr: updatedData.address,
          memo: updatedData.memo,
          dur: updatedData.durationMin
            ? Number(updatedData.durationMin)
            : existing.dur,
          cost: updatedData.budget ? Number(updatedData.budget) : existing.cost,
        },
      };

      if ((chains[activeDay] || []).includes(editingBlockId)) {
        const { newItems, newChain } = resolveOverlaps(
          updatedItems,
          chains[activeDay],
          dayStart[activeDay],
          editingBlockId,
        );
        setChains((pc) => ({ ...pc, [activeDay]: newChain }));
        return newItems;
      }
      return updatedItems;
    });
    setEditingBlockId(null);
  };

  const handleCreateCustomBlock = () => {
    const newId = `custom-${Date.now()}`;
    const newBlock = {
      id: newId,
      cat: "etc",
      sub: "",
      name: "새 일정",
      addr: "",
      memo: "",
      dur: 60,
      startMins: 540,
      cost: 0,
      auto: false,
    };
    setItems((prev) => ({ ...prev, [newId]: newBlock }));
    setPool((prev) => [newId, ...prev]);
    setEditingBlockId(newId);
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
          dayStart[activeDay],
          resizingState.id,
        );
        return newItems;
      });
    };

    const handleGlobalClick = () => setResizingState(null);
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
  }, [resizingState, activeDay, chains, dayStart]);

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
        const relativeY =
          topY - tlRect.top + (timelineDOMRef.current?.scrollTop || 0);
        const calcMins =
          dayStart[activeDay] + Math.round((relativeY - TL_PAD_TOP) / PX);
        let dropMins = Math.round(calcMins / SNAP) * SNAP;
        const dur = items[activeIdLocal]?.dur || 60; // 기본 소요시간 60분
        dropMins = Math.max(
          dayStart[activeDay],
          Math.min(dropMins, DAY_END - dur),
        );
        return { region: "timeline", dropMins, dur };
      }
      return { region: null };
    },
    [pool, chains, activeDay, items, dayStart],
  );

  const handleDragStart = (event) => {
    if (resizingState) return;
    setActiveId(event.active.id);
    activeDragRef.current = event.active;
    setDragPreview(computeDropTarget(event.active));
  };
  const handleDragMove = (event) => {
    activeDragRef.current = event.active;
    setDragPreview(computeDropTarget(event.active));
  };
  const handleDragCancel = () => {
    setActiveId(null);
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
    activeDragRef.current = null;
    setDragPreview(null);

    if (!target || !target.region) return;

    // 💡 1. 검색 결과 항목을 드래그해서 놓았을 때의 처리
    if (isFromSearch) {
      if (target.region === "trash") return; // 휴지통에 놓으면 그냥 무시

      const place = active.data.current.place;
      const newId = `search-${place.id}-${Date.now()}`;

      // 검색 데이터를 우리 앱의 블록 데이터 구조로 변환
      const newBlock = {
        id: newId,
        cat: "spot",
        sub: place.category_group_name || "검색된 장소",
        name: place.place_name,
        addr: place.road_address_name || place.address_name,
        memo: place.phone || "",
        dur: 60, // 기본 소요시간 1시간
        startMins: target.region === "timeline" ? target.dropMins : 540,
        cost: 0,
        auto: false,
      };

      if (target.region === "pool") {
        setItems((prev) => ({ ...prev, [newId]: newBlock }));
        setPool((prev) => {
          const next = [...prev];
          next.splice(
            Math.max(0, Math.min(target.insertIndex, next.length)),
            0,
            newId,
          );
          return next;
        });
      } else if (target.region === "timeline") {
        setItems((prevItems) => {
          let updatedItems = { ...prevItems, [newId]: newBlock };
          let currentDayList = [...(chains[activeDay] || []), newId];
          const { newItems, newChain } = resolveOverlaps(
            updatedItems,
            currentDayList,
            dayStart[activeDay],
            newId,
          );
          setChains((prevChains) => ({ ...prevChains, [activeDay]: newChain }));
          return newItems;
        });
      }
      return;
    }

    // 기존 풀/타임라인 내의 이동 처리 로직 유지
    if (target.region === "trash") {
      setChains((prev) => {
        const next = { ...prev };
        Object.keys(next).forEach((day) => {
          next[day] = next[day].filter((x) => x !== activeIdLocal);
        });
        return next;
      });
      setPool((prev) => prev.filter((x) => x !== activeIdLocal));
      setItems((prev) => {
        const next = { ...prev };
        delete next[activeIdLocal];
        return next;
      });
      return;
    }

    if (target.region === "pool") {
      if (isFromPool) {
        const withoutActive = pool.filter((id) => id !== activeIdLocal);
        withoutActive.splice(
          Math.max(0, Math.min(target.insertIndex, withoutActive.length)),
          0,
          activeIdLocal,
        );
        setPool(withoutActive);
        return;
      }
      setChains((prev) => {
        const next = { ...prev };
        Object.keys(next).forEach((day) => {
          next[day] = next[day].filter((id) => id !== activeIdLocal);
        });
        return next;
      });
      setPool((prev) => {
        if (prev.includes(activeIdLocal)) return prev;
        const next = [...prev];
        next.splice(
          Math.max(0, Math.min(target.insertIndex, next.length)),
          0,
          activeIdLocal,
        );
        return next;
      });
      return;
    }

    if (target.region === "timeline") {
      const dropMins = target.dropMins;
      setItems((prevItems) => {
        let updatedItems = {
          ...prevItems,
          [activeIdLocal]: { ...prevItems[activeIdLocal], startMins: dropMins },
        };
        let currentDayList = [...(chains[activeDay] || [])];
        if (!currentDayList.includes(activeIdLocal))
          currentDayList.push(activeIdLocal);
        const { newItems, newChain } = resolveOverlaps(
          updatedItems,
          currentDayList,
          dayStart[activeDay],
          activeIdLocal,
        );

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
        return newItems;
      });
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
    if (activeDragRef.current?.data?.current?.from === "search") {
      const place = activeDragRef.current.data.current.place;
      draggedItem = {
        id: activeId,
        cat: "spot",
        name: place.place_name,
        sub: place.category_group_name,
        addr: place.road_address_name || place.address_name,
        dur: 60,
        cost: 0,
      };
    } else {
      draggedItem = items[activeId];
    }
  }

  const isDraggingFromPool = activeId ? pool.includes(activeId) : false;
  const isDraggingFromSearch =
    activeDragRef.current?.data?.current?.from === "search";

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
                <div
                  className="board"
                  style={{
                    display: "flex",
                    flexDirection: "column",
                    // 좌우로 넓어진 만큼 세로도 화면을 쓰게 한다(지도·타임라인이 함께 커짐)
                    maxHeight: "min(78vh, 900px)",
                  }}
                >
                  <div className="bd-head" style={{ flexShrink: 0 }}>
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
                    style={{
                      marginTop: "10px",
                      position: "relative",
                      height: `${(timelineEnd - timelineStart) * PX + 120}px`,
                      minHeight: "320px",
                      maxHeight: "min(62vh, 740px)",
                      overflowY: "auto",
                      overflowX: "hidden",
                    }}
                  >
                    <div
                      className="tl-bg"
                      style={{
                        position: "absolute",
                        top: `${TL_PAD_TOP}px`,
                        left: `${TL_PAD_LEFT}px`,
                        right: 0,
                        bottom: 0,
                        pointerEvents: "none",
                        zIndex: 0,
                      }}
                    >
                      {timeSlots.map((t) => (
                        <div
                          key={t}
                          style={{
                            position: "absolute",
                            top: `${(t - timelineStart) * PX}px`,
                            left: 0,
                            width: "100%",
                            height: "1px",
                          }}
                        >
                          <span
                            style={{
                              position: "absolute",
                              left: "-64px",
                              top: "-10px",
                              width: "52px",
                              textAlign: "right",
                              fontSize: "12px",
                              fontWeight: "700",
                              color: "#c9b8a5",
                            }}
                          >
                            {fmtTime(t)}
                          </span>
                          <div
                            style={{
                              position: "absolute",
                              left: "-6px",
                              right: 0,
                              top: 0,
                              borderTop: "1px dashed rgba(61, 43, 34, 0.15)",
                            }}
                          />
                        </div>
                      ))}
                      {dragPreview?.region === "timeline" && draggedItem && (
                        <div
                          style={{
                            position: "absolute",
                            left: "-6px",
                            right: "10px",
                            top: `${(dragPreview.dropMins - timelineStart) * PX}px`,
                            height: `${(dragPreview.dur || draggedItem.dur || 30) * PX}px`,
                            border: `2px dashed ${catOf(draggedItem).hex}`,
                            background: catOf(draggedItem).bg,
                            opacity: 0.75,
                            borderRadius: "12px",
                            zIndex: 8,
                            display: "flex",
                            alignItems: "flex-start",
                            justifyContent: "flex-start",
                            padding: "6px 10px",
                            pointerEvents: "none",
                          }}
                        >
                          <span
                            style={{
                              fontSize: "12px",
                              fontWeight: 800,
                              color: catOf(draggedItem).hex,
                              background: "#fff",
                              borderRadius: "8px",
                              padding: "2px 8px",
                            }}
                          >
                            {fmtTime(dragPreview.dropMins)} 에 놓기
                          </span>
                        </div>
                      )}
                    </div>

                    <div
                      style={{
                        position: "absolute",
                        top: `${TL_PAD_TOP}px`,
                        left: `${TL_PAD_LEFT}px`,
                        right: 0,
                        bottom: "100px",
                        zIndex: 5,
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
                              <div style={{ opacity: 0 }}>
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

                            {/* 💡 업데이트된 부분: 블록과 겹치지 않는 스마트 교통 아이콘 */}
                            {showGapBtn && !isThisActiveTimelineCard && (
                              <div
                                style={{
                                  position: "absolute",
                                  // 시간이 비어있으면 갭의 정중앙에, 딱 붙어있으면 경계선에 배치
                                  top: hasEnoughGap
                                    ? `${(data.endMins + gapMins / 2 - timelineStart) * PX}px`
                                    : `${(data.endMins - timelineStart) * PX}px`,
                                  left: "10px",
                                  right: hasEnoughGap ? "10px" : "15px", // 딱 붙어있을 땐 우측으로 살짝 밀기
                                  zIndex: 30,
                                  display: "flex",
                                  justifyContent: hasEnoughGap
                                    ? "center"
                                    : "flex-end", // 붙어있으면 우측 정렬
                                  alignItems: "center",
                                  transform: "translateY(-50%)",
                                  pointerEvents: "none",
                                }}
                              >
                                <div
                                  style={{
                                    background: "#fff",
                                    border: "1px solid #d97e3c",
                                    borderRadius: "20px",
                                    padding: "6px 14px",
                                    fontSize: "12px",
                                    color: "#d97e3c",
                                    pointerEvents: "auto",
                                    cursor: "pointer",
                                    display: "flex",
                                    alignItems: "center",
                                    gap: "6px",
                                    boxShadow: "0 2px 6px rgba(0,0,0,0.12)",
                                  }}
                                  onClick={(e) => {
                                    e.stopPropagation();
                                    handleAddSingleTransport(
                                      activeDay,
                                      data.id,
                                      nextData.id,
                                    );
                                  }}
                                  // 💡 마우스 호버 시 색상이 바뀌는 효과 추가
                                  onMouseEnter={(e) => {
                                    e.currentTarget.style.background = "#d97e3c";
                                    e.currentTarget.style.color = "#fff";
                                  }}
                                  onMouseLeave={(e) => {
                                    e.currentTarget.style.background = "#fff";
                                    e.currentTarget.style.color = "#d97e3c";
                                  }}
                                >
                                  <span style={{ fontSize: "14px" }}>🚗</span>
                                  <span style={{ fontWeight: "bold" }}>
                                    {hasEnoughGap
                                      ? "이동 시간 계산"
                                      : "이동 추가"}
                                  </span>
                                </div>
                              </div>
                            )}
                          </React.Fragment>
                        );
                      })}

                      {activeDayItems.length === 0 && (
                        <div
                          className="endzone"
                          style={{
                            position: "absolute",
                            top: "40px",
                            left: "10px",
                            right: "10px",
                            pointerEvents: "none",
                            color: "#888",
                            textAlign: "center",
                          }}
                        >
                          ＋ 비어있는 타임라인의 원하는 시간 위치로 드래그하여
                          일정을 추가하세요
                        </div>
                      )}
                    </div>
                  </div>
                </div>

                <div
                  style={{ display: "flex", gap: "16px", alignItems: "stretch" }}
                >
                  <div
                    className={`pool-sec ${dragPreview?.region === "pool" ? "dropover" : ""}`}
                    ref={setPoolRef}
                    style={{ flex: 1, margin: 0 }}
                  >
                    <div
                      className="pool-head"
                      style={{
                        display: "flex",
                        justifyContent: "space-between",
                        alignItems: "center",
                      }}
                    >
                      <div>
                        <b>후보 목록</b> <span className="n">{pool.length}</span>
                        <span
                          style={{
                            fontSize: "12px",
                            color: "#888",
                            marginLeft: "8px",
                          }}
                        >
                          자유롭게 끌어다 놓고 빼세요
                        </span>
                      </div>
                      <button
                        onClick={handleCreateCustomBlock}
                        style={{
                          backgroundColor: "#7c5443",
                          color: "#fff",
                          border: "none",
                          borderRadius: "8px",
                          padding: "6px 14px",
                          fontSize: "13px",
                          fontWeight: "bold",
                          cursor: "pointer",
                          boxShadow: "0 2px 6px rgba(124, 84, 67, 0.2)",
                        }}
                      >
                        + 커스텀 블록 만들기
                      </button>
                    </div>
                    <div
                      className="pool"
                      style={{
                        minHeight: "150px",
                        paddingBottom: "20px",
                        position: "relative",
                      }}
                    >
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
                        <div
                          style={{
                            position: "absolute",
                            inset: 0,
                            border: "2px dashed var(--acc, #9c4a2f)",
                            borderRadius: "14px",
                            pointerEvents: "none",
                            background: "rgba(156, 74, 47, 0.04)",
                          }}
                        />
                      )}
                    </div>
                  </div>

                  <div
                    ref={setTrashRef}
                    className={`trash-zone ${activeId ? "dragging" : ""} ${dragPreview?.region === "trash" ? "dropover" : ""}`}
                    style={{
                      margin: 0,
                      width: "140px",
                      flexDirection: "column",
                      justifyContent: "center",
                      textAlign: "center",
                      gap: "12px",
                      padding: "20px",
                    }}
                  >
                    {/* 개인 페이지의 삭제 버튼과 같은 휴지통 글리프(🗑)를 쓴다 */}
                    <span style={{ fontSize: "32px", display: "block" }}>🗑</span>
                    <span style={{ display: "block", lineHeight: "1.4" }}>
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
                <div
                  className="panel"
                  style={{
                    padding: "24px",
                    backgroundColor: "#fbf8f1",
                    borderRadius: "16px",
                    boxShadow: "0 4px 12px rgba(0,0,0,0.05)",
                    marginBottom: "20px",
                  }}
                >
                  <div style={{ marginBottom: "16px" }}>
                    <span
                      style={{
                        fontSize: "15px",
                        fontWeight: "bold",
                        color: "#666",
                      }}
                    >
                      총{" "}
                    </span>
                    <span
                      style={{
                        fontSize: "28px",
                        fontWeight: "800",
                        color: "#3d2b22",
                      }}
                    >
                      {totalBudget.toLocaleString()}원
                    </span>
                  </div>

                  <div
                    style={{
                      display: "flex",
                      justifyContent: "space-between",
                      alignItems: "center",
                      marginBottom: "12px",
                      fontSize: "13px",
                      color: "#888",
                    }}
                  >
                    <span>희망 총 예산</span>
                    <div
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: "8px",
                        backgroundColor: "#f4f1ea",
                        padding: "4px 8px",
                        borderRadius: "8px",
                      }}
                    >
                      <button
                        onClick={() => handleTargetBudgetChange(-100000)}
                        style={{
                          border: "none",
                          background: "none",
                          cursor: "pointer",
                          fontWeight: "bold",
                          color: "#666",
                        }}
                      >
                        -
                      </button>
                      <span style={{ fontWeight: "bold", color: "#333" }}>
                        {targetBudget.toLocaleString()}원
                      </span>
                      <button
                        onClick={() => handleTargetBudgetChange(100000)}
                        style={{
                          border: "none",
                          background: "none",
                          cursor: "pointer",
                          fontWeight: "bold",
                          color: "#666",
                        }}
                      >
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

                  <div
                    style={{
                      display: "flex",
                      justifyContent: "space-between",
                      fontSize: "12px",
                      color: "#888",
                    }}
                  >
                    <span>희망 예산의 {Math.round(budgetPercent)}% 사용</span>
                    <span
                      style={{
                        color: remainingBudget < 0 ? "#d97e3c" : "#666",
                        fontWeight: remainingBudget < 0 ? "bold" : "normal",
                      }}
                    >
                      {remainingBudget < 0
                        ? `${Math.abs(remainingBudget).toLocaleString()}원 초과`
                        : `남은 ${remainingBudget.toLocaleString()}원`}
                    </span>
                  </div>
                </div>

                <div
                  className="panel"
                  style={{
                    padding: "24px",
                    backgroundColor: "#fbf8f1",
                    borderRadius: "16px",
                    boxShadow: "0 4px 12px rgba(0,0,0,0.05)",
                    marginBottom: "20px",
                  }}
                >
                  <h4
                    style={{
                      margin: "0 0 16px 0",
                      color: "#3d2b22",
                      fontSize: "16px",
                    }}
                  >
                    지도{" "}
                    <span
                      style={{
                        fontSize: "12px",
                        color: "#888",
                        fontWeight: "normal",
                      }}
                    >
                      검색하면 지도가 이동합니다
                    </span>
                  </h4>
                  {/* 높이는 CSS(.map-box)에서 화면 높이에 맞춰 늘린다 — 사이드 폭이
                      넓어진 만큼 지도도 남는 공간을 다 쓰게 하기 위함 */}
                  <div id="kakao-map-container" className="map-box" />
                </div>

                <div
                  className="panel"
                  style={{
                    padding: "24px",
                    backgroundColor: "#fbf8f1",
                    borderRadius: "16px",
                    boxShadow: "0 4px 12px rgba(0,0,0,0.05)",
                  }}
                >
                  <h4
                    style={{
                      margin: "0 0 16px 0",
                      color: "#3d2b22",
                      fontSize: "16px",
                    }}
                  >
                    카카오 장소 검색
                  </h4>
                  <div
                    style={{
                      display: "flex",
                      flexDirection: "column",
                      gap: "16px",
                    }}
                  >
                    <form
                      onSubmit={handleSearchPlace}
                      style={{ display: "flex", gap: "8px" }}
                    >
                      <input
                        type="text"
                        value={searchKeyword}
                        onChange={(e) => setSearchKeyword(e.target.value)}
                        placeholder="도시, 명소, 음식..."
                        style={{
                          flex: 1,
                          padding: "12px",
                          borderRadius: "10px",
                          border: "1px solid #e6dec8",
                          backgroundColor: "#fff",
                          fontSize: "14px",
                          outline: "none",
                          boxSizing: "border-box",
                        }}
                      />
                      <button
                        type="submit"
                        style={{
                          padding: "0 16px",
                          borderRadius: "10px",
                          border: "none",
                          backgroundColor: "#7c5443",
                          color: "#fff",
                          cursor: "pointer",
                          fontWeight: "bold",
                        }}
                      >
                        검색
                      </button>
                    </form>

                    {/* 💡 검색 결과 리스트: 버튼이 사라지고 이젠 꾹 눌러서 끌 수 있습니다! */}
                    <div
                      ref={searchListRef}
                      style={{
                        maxHeight: "250px",
                        overflowY: "auto",
                        display: "flex",
                        flexDirection: "column",
                        gap: "8px",
                      }}
                    >
                      {searchResults.map((place) => (
                        <SearchResultDraggable key={place.id} place={place} />
                      ))}
                      {searchResults.length === 0 && (
                        <div
                          style={{
                            textAlign: "center",
                            color: "#888",
                            fontSize: "13px",
                            padding: "20px 0",
                          }}
                        >
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
                      className="pcard"
                      style={{
                        "--dc": catOf(draggedItem).hex,
                        "--cb": catOf(draggedItem).bg,
                        cursor: "grabbing",
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
                      className="card"
                      style={{
                        "--dc": catOf(draggedItem).hex,
                        "--cb": catOf(draggedItem).bg,
                        width: "320px",
                        cursor: "grabbing",
                        boxShadow: "0 10px 28px rgba(61,43,34,0.22)",
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
        <div
          className="modal-overlay"
          style={{
            position: "fixed",
            top: 0,
            left: 0,
            width: "100vw",
            height: "100vh",
            backgroundColor: "rgba(0,0,0,0.5)",
            zIndex: 9999,
            display: "flex",
            justifyContent: "center",
            alignItems: "center",
          }}
        >
          {(() => {
            const item = items[editingBlockId];
            const sMins = item.startMins;
            const eMins = sMins + item.dur;
            const dayNum = activeDay.replace("d", "");
            const timeStr = `Day ${dayNum} · ${fmtTime(sMins)} - ${fmtTime(eMins)}`;

            return (
              <BlockEditForm
                initialData={item}
                timeString={timeStr}
                onSave={handleSaveBlock}
                onCancel={() => setEditingBlockId(null)}
              />
            );
          })()}
        </div>
      )}

      {viewMode === "edit" && <ChatbotWidget />}
    </>
  );
}
