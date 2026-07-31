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

// ── 블록 id 규약 ─────────────────────────────────────
// 서버에 아직 없는 블록(모달 저장 전의 커스텀 블록)을 구분하는 규약
const isTempId = (id) => String(id).startsWith("custom-");
// 서버에 실재하는 블록만 REST 를 태운다 — custom-(저장 전)·auto-(로컬 교통)는 제외
const isServerBlock = (id) => !isTempId(id) && !String(id).startsWith("auto-");

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
        {/* 💡 연필 아이콘 삭제, 글씨 두께만 강조 */}
        <div className="nm" style={{ fontWeight: "bold", color: "#333" }}>
          {item?.name}
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
          {/* 💡 밑줄 및 연필 아이콘 삭제 */}
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
      </div>
    </div>
  );
}

function ReadModeView({ chains, items, startDate }) {
  // Day 수는 프로젝트 기간에서 파생된 chains 의 키를 그대로 따른다 (useDashboard 가 만든다)
  const days = Object.keys(chains);

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
      onClick={() => onClick && onClick(place)}
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

function ReadModeView({ chains, items }) {

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
    reload,
  } = useDashboard(projectId);

  const [viewMode, setViewMode] = useState("edit");
  const [activeDay, setActiveDay] = useState("d1");
  // Day 시작 시각(타임라인 상단) — 서버에 저장 칸이 없는(ERD) 본인 화면 전용 값.
  // 기본 09:00 고정이고 상단 ± 버튼으로만 바뀐다. 블록 시각에서 파생하지 않는다 —
  // 파생하면 블록을 놓을 때마다 새로고침 후 타임라인 시작이 멋대로 움직인다.
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

  const [map, setMap] = useState(null);
  const [searchKeyword, setSearchKeyword] = useState("");
  const [searchResults, setSearchResults] = useState([]);
  const searchListRef = useRef(null);
  const infoWindowRef = useRef(null);

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
        .updateProject(projectId, { targetBudget: next })
        .catch(rollbackToServer);
    }, 600);
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
          dayStart[dayKey],
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
          dayStart[dayKey],
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
          dayStart[activeDay],
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
          dayStart[activeDay],
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
        dayStart[activeDay],
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

  const timelineStart = dayStart[activeDay];
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
      dayStart[activeDay],
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

  // 스냅샷이 시드되기 전(로딩 중)에는 보드를 그리지 않는다 — dayStart[activeDay]
  // 같은 파생값이 아직 없다. 에러일 때는 위 effect 가 그룹 페이지로 되돌린다.
  if (status !== "loaded" || dayStart[activeDay] == null) return null;

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
        // 💡 사이드바까지 드래그 기능이 통하도록 DndContext를 가장 상위 껍데기로 이동시켰습니다!
        <DndContext
          sensors={sensors}
          autoScroll={dragPreview?.region === "timeline"}
          onDragStart={handleDragStart}
          onDragMove={handleDragMove}
          onDragEnd={handleDragEnd}
          onDragCancel={handleDragCancel}
        >
          <div className="dashboard-page">
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
                                dayStartMins={dayStart[activeDay]}
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
                              dayStartMins={dayStart[activeDay]}
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
                                  ? `${(data.endMins + gapMins / 2 - dayStart[activeDay]) * PX}px`
                                  : `${(data.endMins - dayStart[activeDay]) * PX}px`,
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
                <div
                  id="kakao-map-container"
                  style={{
                    height: "220px",
                    backgroundColor: "#e0e0e0",
                    borderRadius: "12px",
                  }}
                />
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
                      <SearchResultDraggable
                        key={place.id}
                        place={place}
                        onClick={handlePlaceClick} // 💡 만든 함수를 여기에 쏙!
                      />
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
          startDate={project?.startDate}
        />
      )}

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
