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

// 상단 네비게이션 바 (공통 컴포넌트)
import { AppBar } from "../My/shared/ui/AppBar";
// 대시보드 전용 스타일
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

const restrictTimelineX = ({ transform, active, over }) => {
  if (active?.data?.current?.from === "timeline") {
    if (over?.id !== "poolArea" && over?.id !== "trashArea") {
      return { ...transform, x: 0 };
    }
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
      if (start < fixedEnd && end > fixedStart) {
        start = Math.max(start, fixedEnd);
      }
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
  } = useSortable({
    id,
    data: { from: "pool" },
  });
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
  const { attributes, listeners, setNodeRef, transform, isDragging } =
    useDraggable({
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

export function DashboardPage() {
  const { groupId, projectId } = useParams();

  // 데이터 상태 관리
  const [activeDay, setActiveDay] = useState("d1");
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

  // 모달 및 드래그 상태 관리
  const [editingBlockId, setEditingBlockId] = useState(null);
  const [activeId, setActiveId] = useState(null);
  const [resizingState, setResizingState] = useState(null);
  const [dragPreview, setDragPreview] = useState(null);
  const totalBudget = Object.values(items).reduce(
    (sum, item) => sum + (item.cost || 0),
    0,
  );

  const timelineDOMRef = useRef(null);
  const poolDOMRef = useRef(null);
  const trashDOMRef = useRef(null);
  const activeDragRef = useRef(null);
  const dragRegionRef = useRef(null);

  // 모달에서 수정한 데이터를 저장하고 타임라인 재계산하는 함수
  const handleSaveBlock = (updatedData) => {
    setItems((prev) => {
      const existing = prev[editingBlockId];
      // 백엔드 명세와 기존 프론트 상태명(cat, dur, cost) 매핑
      const updatedItems = {
        ...prev,
        [editingBlockId]: {
          ...existing,
          name: updatedData.name,
          cat: updatedData.category
            ? updatedData.category.toLowerCase()
            : existing.cat,
          dur: updatedData.durationMin
            ? Number(updatedData.durationMin)
            : existing.dur,
          cost: updatedData.budget ? Number(updatedData.budget) : existing.cost,
        },
      };

      // 시간이 변경되었을 수 있으므로 충돌/밀어내기 재계산
      if (chains[activeDay].includes(editingBlockId)) {
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
        if (tentativeEnd - resizingState.originalStartMins < 10) {
          tentativeEnd = resizingState.originalStartMins + 10;
        }
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
        ) {
          tentativeStart =
            resizingState.originalStartMins + resizingState.startDur - 10;
        }
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

  const [isGeneratingTransport, setIsGeneratingTransport] = useState(false);
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
  const handleCreateCustomBlock = () => {
    // 겹치지 않는 고유 ID 생성 (현재 시간 활용)
    const newId = `custom-${Date.now()}`;

    // 기본값이 채워진 새 블록 객체
    const newBlock = {
      id: newId,
      cat: "etc", // 기본 카테고리는 '기타'
      sub: "",
      name: "새 일정",
      addr: "",
      memo: "",
      dur: 60, // 기본 60분
      startMins: 540, // 09:00 기본값 (타임라인에 올릴 때 알아서 바뀜)
      cost: 0,
      auto: false,
    };

    // 1. 전체 아이템(items) 목록에 새 블록 추가
    setItems((prev) => ({ ...prev, [newId]: newBlock }));
    // 2. 후보 목록(pool)의 제일 앞쪽에 새 블록 ID 추가
    setPool((prev) => [newId, ...prev]);
    // 3. 생성과 동시에 정보를 수정할 수 있도록 모달 창 띄우기
    setEditingBlockId(newId);
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

  return (
    <>
      {/* 상단 네비게이션 */}
      <AppBar
        crumbs={[
          { label: "그룹", to: `/groups/${groupId}` },
          { label: "프로젝트 대시보드" },
        ]}
      />

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
            {["d1", "d2", "d3", "d4"].map((day, i) => (
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
                  2026.10.0{activeDay.replace("d", "")}
                </span>
                <div className="right">
                  <button
                    className="auto-transport-btn"
                    onClick={() => regenerateAutoTransport(activeDay)}
                    disabled={
                      isGeneratingTransport ||
                      (chains[activeDay] || []).filter((id) => !items[id]?.auto)
                        .length < 2
                    }
                  >
                    {isGeneratingTransport
                      ? "생성 중..."
                      : "🚗 이동수단 자동 생성"}
                  </button>
                  <div className="start-ctl">
                    시작
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
                    return (
                      <TimelineCard
                        key={data.id}
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
                {/* 💡 헤더 영역을 Flex로 묶어 양옆으로 배치 */}
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
                  {/* 💡 커스텀 블록 추가 버튼 */}
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
                  <SortableContext items={pool} strategy={rectSortingStrategy}>
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
                      (items[activeId]?.startMins || 0) + (draggedItem.dur || 0)
                    }
                  />
                </div>
              )
            ) : null}
          </DragOverlay>
        </DndContext>

        <div className="side">
          <div className="panel">
            <h4>예산</h4>
            <div className="bud">
              <span>총</span>{" "}
              <span className="t">{totalBudget.toLocaleString()}원</span>
            </div>
          </div>
          <div className="panel">
            <h4>지도</h4>
            <div className="map">
              <span className="kk">kakao map placeholder</span>
            </div>
          </div>
        </div>
      </div>

      {/* 화면 전체를 덮는 모달 오버레이 */}
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
          <BlockEditForm
            // 기존 프론트 포맷을 BlockEditForm이 요구하는 백엔드 포맷(명세)으로 치환하여 전달
            initialData={{
              id: items[editingBlockId].id,
              name: items[editingBlockId].name,
              category: items[editingBlockId].cat.toUpperCase(),
              durationMin: items[editingBlockId].dur,
              budget: items[editingBlockId].cost,
            }}
            onSave={handleSaveBlock}
            onCancel={() => setEditingBlockId(null)}
          />
        </div>
      )}
      <ChatbotWidget />
    </>
  );
}
