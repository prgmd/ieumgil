import React, { useState, useRef, useEffect, useCallback } from "react";
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

// "2026-08-10" + dayIdx → "2026.08.12" — Day 헤더의 날짜 라벨.
// 기간이 없는 프로젝트(start/end nullable)는 빈 문자열로 라벨을 생략한다.
const dayDateLabel = (startDate, dayIdx) => {
  if (!startDate) return "";
  const d = new Date(startDate);
  d.setDate(d.getDate() + dayIdx);
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}.${mm}.${dd}`;
};

const restrictTimelineX = ({ transform, active, over }) => {
  if (active?.data?.current?.from === "timeline") {
    if (over?.id !== "poolArea" && over?.id !== "trashArea")
      return { ...transform, x: 0 };
  }
  return transform;
};

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

function DayTab({ label, count, isActive, onClick }) {
  return (
    <button className={`day-tab ${isActive ? "on" : ""}`} onClick={onClick}>
      {label} <span className="cnt">{count}</span>
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
        <div
          className="nm"
          onClick={(e) => {
            e.stopPropagation();
            onEditBlock && onEditBlock(id);
          }}
          style={{ cursor: "pointer" }}
          title="클릭하여 상세 편집"
        >
          {item?.name} ✏️
        </div>
        <div className="sub">{item?.detail || item?.address}</div>
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
          <span
            className="nm"
            onClick={(e) => {
              if (isThisResizing) return;
              e.stopPropagation();
              onEditBlock && onEditBlock(id);
            }}
            style={{
              cursor: "pointer",
              textDecoration: "underline",
              textUnderlineOffset: "3px",
            }}
            title="클릭하여 상세 내용 수정"
          >
            {item?.name} ✏️
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
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className="pcard"
      data-pool-id={id}
      {...attributes}
      {...listeners}
    >
      <CardBody id={id} item={item} mode="pool" onEditBlock={onEditBlock} />
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
    cursor: isThisResizing ? "ns-resize" : "grab",
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
      </div>
    </div>
  );
}

function ReadModeView({ chains, items, startDate }) {
  // Day 수는 프로젝트 기간에서 파생된 chains 의 키를 그대로 따른다 (useDashboard 가 만든다)
  const days = Object.keys(chains);
  return (
    <div
      style={{
        padding: "20px 40px",
        backgroundColor: "#f4f1ea",
        minHeight: "100vh",
      }}
    >
      {days.map((day, index) => {
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
            <div style={{ marginBottom: "24px" }}>
              <h2
                style={{
                  fontSize: "26px",
                  color: "#3d2b22",
                  margin: "0 0 8px 0",
                  fontFamily: "serif",
                }}
              >
                Day {index + 1}{" "}
                <span
                  style={{
                    fontSize: "15px",
                    color: "#888",
                    fontWeight: "normal",
                    fontFamily: "sans-serif",
                  }}
                >
                  {dayDateLabel(startDate, index)}
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
  } = useDashboard(projectId);

  // 모드 상태 ('edit' or 'read')
  const [viewMode, setViewMode] = useState("edit");

  const [activeDay, setActiveDay] = useState("d1");
  // Day 시작 시각 — 서버에 저장 칸이 없다(ERD 가 day_settings 를 제거:
  // Day 시작 = 그 Day 첫 블록의 start_time). 시드 때 첫 블록 시각으로 파생하고,
  // 이후 ± 조절은 본인 화면에서만 유효한 로컬 값이다.
  const [dayStart, setDayStart] = useState({});

  // 보드 편집 상태 — 초기값은 비워 두고, 스냅샷이 도착하면 아래 시드 effect 가 채운다.
  const [items, setItems] = useState({});
  const [chains, setChains] = useState({});
  const [pool, setPool] = useState([]);

  const [editingBlockId, setEditingBlockId] = useState(null);
  const [activeId, setActiveId] = useState(null);
  const [resizingState, setResizingState] = useState(null);
  const [dragPreview, setDragPreview] = useState(null);
  const [isGeneratingTransport, setIsGeneratingTransport] = useState(false);

  // 총 예산 자동 계산
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

    // Day 시작 시각 = 그 Day 첫 블록의 시각, 블록이 없으면 09:00
    const starts = {};
    for (const [dayKey, chain] of Object.entries(serverChains)) {
      const first = chain
        .map((id) => serverItems[id])
        .find((b) => b?.startMins != null);
      starts[dayKey] = first ? first.startMins : 540;
    }
    setDayStart(starts);

    // 다른 프로젝트에서 넘어온 경우 이전 프로젝트의 Day 탭이 남지 않게 한다
    if (!serverChains[activeDay]) setActiveDay("d1");

    // 목표 예산은 스냅샷의 project 에 실려 온다. 수정을 저장할 엔드포인트는 아직
    // 백엔드에 없어(PATCH /projects 는 name·기간만 받는다) 위젯의 조절은 로컬 표시용이다.
    setTargetBudget(project?.targetBudget ?? 0);
  }

  // 없는 프로젝트·비멤버·잘못된 URL 이면 그룹 페이지로 되돌린다 (GroupPage 와 같은 규칙)
  useEffect(() => {
    if (status !== "error") return;
    showToast(error?.message ?? "프로젝트를 열 수 없어요.");
    navigate(`/groups/${groupId}`, { replace: true });
  }, [status, error, groupId, navigate, showToast]);
  const handleTargetBudgetChange = (amount) => {
    setTargetBudget((prev) => Math.max(0, prev + amount)); // 0원 밑으로는 안 내려가게 방지
  };
  const budgetPercent =
    targetBudget > 0 ? Math.min(100, (totalBudget / targetBudget) * 100) : 0;
  const remainingBudget = targetBudget - totalBudget;

  // 임시 목업 — 6단계에서 GET /api/transit/route 로 교체한다(BUS/SUBWAY만 구현됨).
  // 인자(출발/도착 블록)는 그때 다시 받는다.
  const fetchTransitInfo = useCallback(async () => {
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

  // 💡 특정 블록 2개 사이에만 교통수단 단일 추가하는 로직
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

  // 서버에 아직 없는 블록(모달 저장 전의 커스텀 블록)을 구분하는 규약
  const isTempId = (id) => String(id).startsWith("custom-");
  // 서버에 실재하는 블록만 REST 를 태운다 — custom-(저장 전)·auto-(로컬 교통)는 제외
  const isServerBlock = (id) =>
    !isTempId(id) && !String(id).startsWith("auto-");

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
          dayStart[activeDay],
          targetId,
        );

        const shifted = chains[activeDay].filter(
          (id) =>
            id !== targetId &&
            isServerBlock(id) &&
            newItems[id]?.startMins != null &&
            newItems[id].startMins !== items[id]?.startMins,
        );
        await Promise.all(
          shifted.map((id) =>
            blockApi.updateBlockFields(id, {
              startTime: blockApi.minsToTime(newItems[id].startMins),
              endTime: blockApi.minsToTime(
                newItems[id].startMins + newItems[id].dur,
              ),
            }),
          ),
        );

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
      [activeDay]: Math.max(300, Math.min(1380, prev[activeDay] + delta)),
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
        const dur = items[activeIdLocal]?.dur || 30;
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
    const target = computeDropTarget(active);

    setActiveId(null);
    activeDragRef.current = null;
    setDragPreview(null);

    if (!target || !target.region) return;

    if (target.region === "trash") {
      // async 삭제(서버 왕복 포함)는 별도 함수로 — 드래그 핸들러는 동기로 끝낸다
      handleDeleteBlock(activeIdLocal);
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

  const timelineStart = dayStart[activeDay];
  const timelineEnd = DAY_END;
  const timeSlots = [];
  for (let t = timelineStart; t <= timelineEnd; t += 30) timeSlots.push(t);

  const draggedItem = activeId ? items[activeId] : null;
  const isDraggingFromPool = activeId ? pool.includes(activeId) : false;

  let displayItems = items;
  let displayChain = chains[activeDay] || [];

  if (dragPreview?.region === "timeline" && activeId && items[activeId]) {
    const draggedDur = dragPreview.dur || items[activeId].dur || 30;
    const tempItems = {
      ...items,
      [activeId]: {
        ...items[activeId],
        startMins: dragPreview.dropMins,
        dur: draggedDur,
      },
    };
    const tempChain = [...displayChain];
    if (!tempChain.includes(activeId)) tempChain.push(activeId);
    const { newItems, newChain } = resolveOverlaps(
      tempItems,
      tempChain,
      dayStart[activeDay],
      activeId,
    );
    displayItems = newItems;
    displayChain = newChain;
  }

  const activeDayItems = displayChain
    .filter((id) => !(isDraggingFromPool && id === activeId))
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

  // 스냅샷이 시드되기 전(로딩 중)에는 보드를 그리지 않는다 — dayStart[activeDay]
  // 같은 파생값이 아직 없다. 에러일 때는 위 effect 가 그룹 페이지로 되돌린다.
  if (status !== "loaded" || dayStart[activeDay] == null) return null;

  return (
    <>
      <AppBar
        crumbs={[
          { label: "그룹", to: `/groups/${groupId}` },
          { label: project?.name ?? "프로젝트 대시보드" },
        ]}
      />

      <div
        style={{
          display: "flex",
          justifyContent: "flex-end",
          padding: "12px 30px",
          backgroundColor: "#f4f1ea",
        }}
      >
        <div
          style={{
            display: "flex",
            gap: "4px",
            backgroundColor: "#f0ebd8",
            padding: "6px",
            borderRadius: "24px",
          }}
        >
          <button
            onClick={() => setViewMode("edit")}
            style={{
              padding: "8px 16px",
              borderRadius: "20px",
              border: "none",
              cursor: "pointer",
              fontSize: "14px",
              transition: "all 0.2s",
              backgroundColor: viewMode === "edit" ? "#fff" : "transparent",
              fontWeight: viewMode === "edit" ? "bold" : "normal",
              color: viewMode === "edit" ? "#7c5443" : "#8c7b70",
              boxShadow:
                viewMode === "edit" ? "0 2px 6px rgba(0,0,0,0.1)" : "none",
            }}
          >
            ✏️ 편집
          </button>
          <button
            onClick={() => setViewMode("read")}
            style={{
              padding: "8px 16px",
              borderRadius: "20px",
              border: "none",
              cursor: "pointer",
              fontSize: "14px",
              transition: "all 0.2s",
              backgroundColor: viewMode === "read" ? "#fff" : "transparent",
              fontWeight: viewMode === "read" ? "bold" : "normal",
              color: viewMode === "read" ? "#7c5443" : "#8c7b70",
              boxShadow:
                viewMode === "read" ? "0 2px 6px rgba(0,0,0,0.1)" : "none",
            }}
          >
            ≡ 읽기
          </button>
        </div>
      </div>

      {viewMode === "edit" ? (
        <div className="dashboard-page">
          <DndContext
            sensors={sensors}
            autoScroll={dragPreview?.region === "timeline"}
            onDragStart={handleDragStart}
            onDragMove={handleDragMove}
            onDragEnd={handleDragEnd}
            onDragCancel={handleDragCancel}
          >
            <div className="daycol">
              {Object.keys(chains).map((day, i) => (
                <DayTab
                  key={day}
                  label={`Day ${i + 1}`}
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
                  maxHeight: "620px",
                }}
              >
                <div className="bd-head" style={{ flexShrink: 0 }}>
                  <h2>Day {activeDay.replace("d", "")}</h2>
                  <span className="date">
                    {dayDateLabel(
                      project?.startDate,
                      Number(activeDay.replace("d", "")) - 1,
                    )}
                  </span>
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
                      <b>{fmtTime(dayStart[activeDay])}</b>
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
                    minHeight: "300px",
                    maxHeight: "420px",
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

                  {/* 💡 중복 렌더링 에러가 있던 부분을 깔끔하게 하나로 정리했습니다. */}
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
                          : dayStart[activeDay];

                      const nextData = activeDayItems[index + 1];
                      const showGapBtn =
                        nextData &&
                        data.item.cat !== "trans" &&
                        nextData.item.cat !== "trans";

                      return (
                        <React.Fragment key={data.id}>
                          <TimelineCard
                            id={data.id}
                            item={data.item}
                            startMins={data.startMins}
                            endMins={data.endMins}
                            resizingState={resizingState}
                            onResizeStart={handleResizeStart}
                            dayStartMins={dayStart[activeDay]}
                            boundTop={boundTop}
                            onEditBlock={setEditingBlockId}
                          />

                          {/* 💡 프로토타입 스타일의 이동수단 추가 아이콘 */}
                          {showGapBtn && (
                            <div
                              style={{
                                position: "absolute",
                                top: `${(data.endMins - dayStart[activeDay]) * PX}px`,
                                left: "10px",
                                right: "10px",
                                zIndex: 30,
                                display: "flex",
                                justifyContent: "center",
                                alignItems: "center",
                                transform: "translateY(-50%)",
                                pointerEvents: "none",
                              }}
                            >
                              <div
                                style={{
                                  background: "#fbf8f1",
                                  border: "1px dashed #d97e3c",
                                  borderRadius: "16px",
                                  padding: "4px 16px",
                                  fontSize: "12px",
                                  color: "#d97e3c",
                                  pointerEvents: "auto",
                                  cursor: "pointer",
                                  display: "flex",
                                  alignItems: "center",
                                  gap: "8px",
                                  boxShadow: "0 2px 6px rgba(0,0,0,0.08)",
                                }}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  handleAddSingleTransport(
                                    activeDay,
                                    data.id,
                                    nextData.id,
                                  );
                                }}
                              >
                                <span>
                                  △ {data.item.name} ➔ {nextData.item.name}
                                </span>
                                <span
                                  style={{
                                    color: "#888",
                                    fontWeight: "normal",
                                  }}
                                >
                                  이동이 비었어요
                                </span>
                                <span
                                  style={{
                                    background: "#d97e3c",
                                    color: "#fff",
                                    padding: "2px 8px",
                                    borderRadius: "4px",
                                    fontSize: "11px",
                                    fontWeight: "bold",
                                  }}
                                >
                                  계산
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
                  <span style={{ fontSize: "32px", display: "block" }}>🗑️</span>
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

            <DragOverlay modifiers={[restrictTimelineX]}>
              {activeId && draggedItem ? (
                isDraggingFromPool ? (
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
          </DndContext>

          <div className="side">
            {/* 💡 예산 패널 */}
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

              {/* 목표 예산 증감 컨트롤러 */}
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

              {/* 프로그레스 바 */}
              <div
                style={{
                  width: "100%",
                  height: "8px",
                  backgroundColor: "#e6dec8",
                  borderRadius: "4px",
                  marginBottom: "10px",
                  overflow: "hidden",
                }}
              >
                <div
                  style={{
                    width: `${budgetPercent}%`,
                    height: "100%",
                    backgroundColor:
                      remainingBudget < 0 ? "#d97e3c" : "#7c5443",
                    transition: "width 0.3s ease, background-color 0.3s ease",
                  }}
                />
              </div>

              {/* 퍼센트 및 남은 금액 텍스트 */}
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

            {/* 지도 패널 */}
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
                  블록을 클릭하면 위치가 표시돼요
                </span>
              </h4>
              <div
                className="map"
                style={{
                  height: "220px",
                  backgroundColor: "#C1D3C4",
                  borderRadius: "12px",
                  display: "flex",
                  justifyContent: "center",
                  alignItems: "center",
                  color: "#fff",
                  fontWeight: "bold",
                }}
              >
                <span className="kk">kakao map placeholder</span>
              </div>
            </div>

            {/* 💡 장소 검색 패널 추가 */}
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
                장소 검색
              </h4>
              <div
                style={{
                  display: "flex",
                  flexDirection: "column",
                  gap: "16px",
                }}
              >
                <input
                  type="text"
                  placeholder="도시, 명소, 음식..."
                  style={{
                    width: "100%",
                    padding: "14px",
                    borderRadius: "10px",
                    border: "1px solid #e6dec8",
                    backgroundColor: "#fff",
                    fontSize: "14px",
                    outline: "none",
                    boxSizing: "border-box",
                  }}
                />

                {/* 검색 결과 더미 데이터 */}
                <div
                  style={{
                    display: "flex",
                    justifyContent: "space-between",
                    alignItems: "center",
                    padding: "4px",
                  }}
                >
                  <div
                    style={{
                      display: "flex",
                      alignItems: "flex-start",
                      gap: "8px",
                    }}
                  >
                    <div
                      style={{
                        color: "#d97e3c",
                        fontSize: "10px",
                        marginTop: "4px",
                      }}
                    >
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
                        돼지국밥 골목
                      </div>
                      <div style={{ fontSize: "12px", color: "#888" }}>
                        부산 서면
                      </div>
                    </div>
                  </div>
                  <button
                    style={{
                      backgroundColor: "#7c5443",
                      color: "#fff",
                      border: "none",
                      borderRadius: "20px",
                      padding: "6px 12px",
                      fontSize: "12px",
                      fontWeight: "bold",
                      cursor: "pointer",
                      display: "flex",
                      alignItems: "center",
                      gap: "4px",
                    }}
                  >
                    + 📍
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      ) : (
        <ReadModeView
          chains={chains}
          items={items}
          startDate={project?.startDate}
        />
      )}

      {/* 모달 렌더링 영역 */}
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
