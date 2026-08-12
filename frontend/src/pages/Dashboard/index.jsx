import React, {
  useState,
  useRef,
  useEffect,
  useCallback,
  useMemo,
} from "react";
import { useParams, useNavigate } from "react-router-dom";
import { BlockEditModal } from "./components/BlockEditModal";
import { ChatbotWidget } from "./components/ChatbotWidget";
import { RemoteCursorLayer } from "./components/RemoteCursorLayer";
import { TransitPickerModals } from "./components/TransitPickerModals";
import { VoiceBar } from "./components/VoiceBar";
import { DayTab } from "./components/DayTab";
import { HintIcon } from "./components/HintIcon";
import { TimelineCard } from "./components/TimelineCard";
import { PoolPanel } from "./components/PoolPanel";
import { BudgetPanel } from "./components/BudgetPanel";
import { MapPanel } from "./components/MapPanel";
import { SearchPanel } from "./components/SearchPanel";
import { ReadModeView } from "./components/ReadModeView";
import { DragOverlayPreview } from "./components/DragOverlayPreview";
import {
  fmtTime,
  fmtTimeLong,
  catOf,
  isServerBlock,
  catFromKakaoGroup,
  dayNoOf,
  dayKeysOf,
  dayDate,
  boardOf,
  blocksOfDay,
  catKeyOf,
  PX,
  TL_PAD_TOP,
  firstStartOf,
} from "./dashboardHelpers";
import { useKakaoMap } from "./hooks/useKakaoMap";
import { useDayNav } from "./hooks/useDayNav";
import { useBudget } from "./hooks/useBudget";
import { useBlockCrud } from "./hooks/useBlockCrud";
import { usePresence } from "./hooks/usePresence";
import { useTransitPicker } from "./hooks/useTransitPicker";
import { useBoardStore } from "./stores/useBoardStore";
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  useDroppable,
} from "@dnd-kit/core";
import {
  sortableKeyboardCoordinates,
} from "@dnd-kit/sortable";
import {
  safeKeyBetween,
  neighborKeysAround,
  insertByOrderKey,
  resolveOverlaps,
  persistMovedOffsets,
} from "./boardOrdering";
import { AppBar } from "../My/shared/ui/AppBar";
import EditProjectModal from "../Group/components/EditProjectModal";
import { useDashboard } from "../../features/dashboard/hooks/useDashboard";
import { useProjectOps } from "../../features/dashboard/realtime/useProjectOps";
import { useVoiceChat } from "../../features/dashboard/voice/useVoiceChat";
import { createOpSequencer } from "../../features/dashboard/realtime/opSequencer";
import * as blockApi from "../../features/dashboard/api/dashboardApi";
import { getClientId } from "../../global/api/clientId";
import { EmptyState } from "../../global/components/EmptyState";
import notFoundImg from "../../assets/img/notfound.png";
import "../Error/error.css";
import { useGroupDetail } from "../../features/group/hooks/useGroupDetail";
import { useProjects } from "../../features/group/hooks/useProjects";
import { useIsMobile } from "../../global/hooks/useIsMobile";
import { useAuthStore } from "../../global/stores/authStore";
import { useToastStore } from "../../global/stores/toastStore";
import { ROUTES } from "../../global/constants/routes";
import "./index.css";

// 블록 사이 "이동 추가" 갭 버튼의 최소 높이(px). 빈 시간이 이보다 좁아도 이만큼은
// 확보해 경계선에 걸쳐 클릭할 수 있게 한다.
const TRANS_GAP_MIN_PX = 16;
// 드래그 스냅 1분 (QA ⓑ) — 10분 스냅이던 시절엔 분 단위 교통블록(실제 API 소요시간)
// 과 완벽하게 맞물리지 않았다. 리사이즈는 원래 분 단위라 이제 둘이 같은 정밀도다.
const SNAP = 1;
// 소요시간 상한(분) — 서버 검증(@Max(1440)·MAX_DURATION_MIN)과 같은 값을 유지해야 한다
const MAX_DUR = 1440;
const TL_PAD_LEFT = 92;

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
  // 커서 위치 수신(applyCursorRef)은 usePresence 가 반환하는 applyCursorMessage 로
  // 아래에서 꽂는다 — 레이어 라우팅·성능 격리는 훅 안에서 처리한다.
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
  // 가장 많이 차지하는 Day 다(useDayNav 의 dominantDayOf). 이름은 activeDay 그대로 둔다:
  // 값의 출처만 바뀌었고 소비처(탭 하이라이트·presence·커서 dayNo·지도)는 그대로
  // 이 값을 읽는다. 탭 클릭은 값을 바꾸는 대신 그 Day 로 스크롤한다(jumpToDay).
  const [scrolledDay, setScrolledDay] = useState("d1");
  // 기간이 줄어 보고 있던 Day 가 사라지면 첫째 날을 본다 — 상태를 되돌리지 않고
  // 렌더 시점에 정하므로 "없는 Day 를 가리키는 한 프레임"이 생기지 않는다.
  const activeDay = dayKeys.includes(scrolledDay) ? scrolledDay : dayKeys[0];

  // Day 목록 내부 스크롤 컨테이너 + 활성 탭. 여행이 길어 목록이 뷰포트를 넘으면
  // 컨테이너 안에서만 스크롤되고, 활성 Day 가 목록 밖으로 나가면 자동으로 보이게 한다.
  const activeTabRef = useRef(null);
  useEffect(() => {
    activeTabRef.current?.scrollIntoView({ block: "nearest" });
  }, [activeDay]);

  // 타임라인 좌표계 = 블록의 startOffsetMinutes 와 같은 공간(Day 1 00:00 기준 절대 분).
  // 축은 여행 전체다 — 첫 Day 의 00:00 부터 마지막 Day 의 24:00 까지 한 줄로 잇는다.
  // 렌더 식은 전부 (값 - timelineStart) * PX 꼴 그대로 두고 기준선만 0 으로 내렸다.
  // dayKeysOf 는 항상 Day 를 하나 이상 주므로 activeDay 는 언제나 "dN" 이다.
  const activeDayIndex = dayNoOf(activeDay) - 1;
  const timelineStart = 0;
  const timelineEnd = dayKeys.length * blockApi.MINUTES_PER_DAY;

  // ── 렌더 창 = 보고 있는 Day 의 앞뒤 한 Day 씩 ────────────────
  // 축은 여행 전체지만 DOM 에 올리는 것은 이 구간뿐이다. 30일 여행이면 눈금만
  // 1441개인데 한 화면에 들어오는 건 Day 의 1/3 남짓이라, 나머지는 스크롤이
  // 그리로 갈 때 만들면 된다.
  // **컨테이너 높이는 줄이지 않는다**(아래 contentEnd) — scrollTop ↔ 절대 분
  // 변환이 computeDropTarget 의 역산과 같은 식이어야 드롭이 산다. 높이를 창에
  // 맞추면 스크롤바도 그 변환도 거짓말이 된다. 줄이는 것은 내용뿐이다.
  // 앞뒤로 한 Day 씩 더 얹는 이유는 둘이다: 화면이 Day 경계에 걸쳐 있을 때,
  // 그리고 활성 Day 가 rAF 한 프레임 늦게 따라올 때 빈 칸이 보이지 않게.
  // 위 경계는 창이 **마지막 Day 를 품는 순간 사라진다**(Infinity). 마지막 자정
  // 너머로 밀린 블록에는 뒤따르는 Day 가 없어 여기 말고 들어갈 창이 없기 때문이다.
  // 유한하게 두면 startMins < windowEnd 가 그 블록에 대해 영영 거짓이라 어떤
  // 스크롤 위치에서도 마운트되지 않는다 — 컨테이너만 contentEnd 까지 늘어난 빈
  // 꼬리가 되고, 그 블록은 보이지도 지우지도 못하는 채로 보드 시간을 점유해
  // (lastEnd) 근처의 드롭을 이유 없이 막는다. resolveOverlaps 에 상한 클램프가
  // 없어 마지막 Day 늦은 시각에 놓기만 해도 이웃이 그리로 밀린다 — 흔한 길이다.
  // 눈금은 여기까지 따라가지 않는다(그릴 Day 가 없다) — 아래 tickEnd 가 자른다.
  const windowStart =
    Math.max(0, dayNoOf(activeDay) - 2) * blockApi.MINUTES_PER_DAY;
  const windowEnd =
    dayNoOf(activeDay) + 1 >= dayKeys.length
      ? Infinity
      : (dayNoOf(activeDay) + 1) * blockApi.MINUTES_PER_DAY;

  // 보드 편집 상태 — 초기값은 비워 두고, 스냅샷이 도착하면 아래 시드 effect 가 채운다.
  const items = useBoardStore((s) => s.items);
  const setItems = useBoardStore((s) => s.setItems);
  const pool = useBoardStore((s) => s.pool);
  const setPool = useBoardStore((s) => s.setPool);
  const resetBoard = useBoardStore((s) => s.resetBoard);
  // 후보 목록 필터 — 대분류(cat) + 제목 검색. 렌더만 거른다(원본 pool 은 그대로).
  const [poolCat, setPoolCat] = useState("ALL");
  const [poolQuery, setPoolQuery] = useState("");
  const poolFilterActive = poolCat !== "ALL" || poolQuery.trim() !== "";
  const visiblePool = useMemo(() => {
    const q = poolQuery.trim().toLowerCase();
    return pool.filter((id) => {
      const it = items[id];
      if (!it) return false;
      if (poolCat !== "ALL" && catKeyOf(it) !== poolCat) return false;
      if (q && !(it.name || "").toLowerCase().includes(q)) return false;
      return true;
    });
  }, [pool, items, poolCat, poolQuery]);

  // 보드(시간축에 올라간 블록들, 오프셋 순) — 상태가 아니라 items 에서 파생한다.
  // 소속의 근거는 오프셋 하나뿐이다: startMins 가 있으면 보드, 없으면 후보(POOL).
  // 따로 목록을 들고 있으면 "오프셋이 가리키는 Day"와 "들어 있는 목록"이 갈라져,
  // 자정 너머로 밀린 블록이 조용히 사라지거나 저장되지 않는 상태가 생긴다.
  // Day 별로 필요한 곳은 blocksOfDay 선택자로 얻는다.
  const board = useMemo(() => boardOf(items), [items]);

  // ── 타임라인 DOM 참조 + 편집 대상 — usePresence 의 입력이라 그 호출보다 위에 둔다 ──
  // (원래 아래에 있던 선언을 최소 이동으로 끌어올린 것뿐이다: 순수 store 선택자와
  //  useRef 라 위치를 바꿔도 부작용이 없다.)
  const timelineDOMRef = useRef(null);
  const editingBlockId = useBoardStore((s) => s.editingBlockId);
  const setEditingBlockId = useBoardStore((s) => s.setEditingBlockId);

  // ── 함께 있는 느낌 (6·7단계) — presence/커서/락 클러스터는 usePresence 로 분리 ──
  // 송신 채널 sendCursor 는 입력으로 넣고, 적용 함수(applyPresenceMessage/
  // applyCursorMessage)는 반환받아 아래 ref 에 꽂는다 — useProjectOps 와의 순환 회피.
  const {
    boardMembers,
    setBoardMembers,
    onlineIds,
    setOnlineIds,
    detailLocks,
    setLastEditors,
    recordBlockEditor,
    nicknameOf,
    lockBadgeOf,
    editorBadgeOf,
    dayViewersOf,
    handlePageCursorMove,
    registerTlCursorHandler,
    registerPageCursorHandler,
    applyPresenceMessage,
    applyCursorMessage,
    pageDOMRef,
  } = usePresence({
    activeDay,
    timelineDOMRef,
    sendCursor,
    currentUser,
    items,
    editingBlockId,
    timelineStart,
    TL_PAD_LEFT,
  });

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

  // 커서 적용 함수도 usePresence 반환값이다 — 이 ref 로 useProjectOps 에 물린다.
  useEffect(() => {
    applyCursorRef.current = applyCursorMessage;
  });

  // (기간이 줄어 범위를 벗어난 블록을 후보 목록으로 되돌리는 일은 서버가 한다 —
  //  PATCH /projects 가 그 블록들을 POOL 로 옮기고 movedToPool 로 알려준다. 기간이
  //  바뀌는 경로는 전부 reload() 로 스냅샷을 다시 읽으므로 로컬에서 한 벌 더 감사할
  //  것이 없다. 예전의 로컬 감사는 "여행 기간 밖 오프셋"을 전부 잡아내서, 겹침
  //  해소로 마지막 자정 너머까지 밀린 블록까지 후보로 끌어내리고 있었다.)

  const [activeId, setActiveId] = useState(null);
  const [resizingState, setResizingState] = useState(null);
  const [dragPreview, setDragPreview] = useState(null);

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
    [focusPlace, items, setEditingBlockId],
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

  // ── 프로젝트 전환 시 보드 스토어 리셋 (모듈 싱글턴 stale 방지) ──
  // 스토어는 모듈 싱글턴이라 언마운트/재마운트·프로젝트 이동 시 이전 보드가 남는다.
  // projectId 가 바뀌는 동안 useDashboard 는 isStale 로 status="loading" 을 강제하므로
  // 아래 시드(status==="loaded" 조건)는 fetch 완료 이후 렌더에서만 실행된다 — 즉 이
  // effect(projectId 변경 시에만 발화)보다 항상 뒤 렌더라 시드를 덮지 않는다.
  useEffect(() => {
    resetBoard();
  }, [projectId, resetBoard]);

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
  }, [setItems, setPool]);

  // ── 교통 피커 (후보 생성·단일 추가·선택 확정·재선택·자동 재생성) ──
  const {
    isGeneratingTransport,
    regenerateAutoTransport,
    bulkTransitPicker,
    setBulkTransitPicker,
    setBulkChoice,
    confirmBulkTransit,
    handleAddSingleTransport,
    transitPicker,
    setTransitPicker,
    setTransitPickerCandidate,
    confirmTransitChoice,
    transportReselectPicker,
    setTransportReselectPicker,
    handleReselectTransport,
    setReselectCandidate,
    applyReselectTransport,
  } = useTransitPicker({
    items,
    setItems,
    board,
    projectId,
    adoptServerId,
    rollbackToServer,
    setEditingBlockId,
    showToast,
  });

  const poolDOMRef = useRef(null);
  const activeDragRef = useRef(null);
  const dragRegionRef = useRef(null);

  // ── 원격 op 적용 ─────────────────────────────────────
  // 원격 블록을 startMins(절대 오프셋)가 가리키는 자리로 놓는다(생성·이동 공용).
  // 시간축 위 자리는 items 갱신만으로 끝난다 — 보드 목록이 오프셋에서 파생하기
  // 때문이다. 여기서 챙길 것은 후보(POOL) 목록뿐이고, 후보의 순서는 시각이 없어
  // 손 정렬(orderKey)만이 근거다.
  const placeRemoteBlock = (block) => {
    setPool((prev) => {
      const without = prev.filter((id) => id !== block.id);
      return block.startMins == null
        ? insertByOrderKey(without, useBoardStore.getState().items, block)
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
        const base = useBoardStore.getState().items[id];
        if (!base) break; // 모르는 블록(이미 삭제 등) — 무시
        const patch = blockApi.serverFieldsToUiPatch(payload.fields);
        const updated = { ...base, ...patch };
        setItems((prev) => (prev[id] ? { ...prev, [id]: updated } : prev));
        break;
      }
      case "BLOCK_MOVED": {
        recordBlockEditor(payload.blockId, op.actorId);
        // 자기 op 도 적용한다 — 이동은 마지막 쓰기가 이긴다. 남이 먼저 옮긴 op 에
        // 덮인 자리를 자기 echo 가 제 위치로 되돌려 놓는 것이 이 재적용의 목적이다.
        const base = useBoardStore.getState().items[payload.blockId];
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
        navigate(ROUTES.group(groupId), { replace: true });
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

  const {
    handleSaveBlock,
    handleCreateCustomBlock,
    handleCopyBlock,
    handleDeleteBlock,
    handleCancelEdit,
  } = useBlockCrud({
    items,
    setItems,
    pool,
    setPool,
    board,
    editingBlockId,
    setEditingBlockId,
    detailLocks,
    currentUser,
    projectId,
    adoptServerId,
    rollbackToServer,
    showToast,
  });

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
        // 리사이즈 중 타임라인이 자동 스크롤되면, 그 스크롤량만큼 delta 를 보정해야
        // 블록이 포인터를 따라온다 — 시작 시점의 scrollTop 을 기준으로 잡는다.
        startScrollTop: timelineDOMRef.current?.scrollTop ?? 0,
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
      const current = useBoardStore.getState().items;
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
    const el = timelineDOMRef.current;
    const EDGE = 36; // 위/아래 이 픽셀 안에 포인터가 오면 자동 스크롤
    const SPEED = 9; // 프레임당 스크롤 px
    let lastClientY = resizingState.startY;
    let raf = null;

    // 현재 포인터 위치 + 그새 스크롤된 양을 합쳐 블록 크기를 다시 계산·반영한다.
    const apply = (clientY) => {
      const scrollTop = el ? el.scrollTop : 0;
      const deltaY =
        clientY -
        resizingState.startY +
        (scrollTop - resizingState.startScrollTop);
      const deltaMins = Math.round(deltaY / PX);
      let newDur = resizingState.startDur;
      let newStart = resizingState.originalStartMins;

      if (resizingState.direction === "bottom") {
        let tentativeEnd =
          resizingState.originalStartMins + resizingState.startDur + deltaMins;
        if (tentativeEnd - resizingState.originalStartMins < 10)
          tentativeEnd = resizingState.originalStartMins + 10;
        if (tentativeEnd - resizingState.originalStartMins > MAX_DUR)
          tentativeEnd = resizingState.originalStartMins + MAX_DUR;
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
        if (newDur > MAX_DUR) {
          newStart =
            resizingState.originalStartMins + resizingState.startDur - MAX_DUR;
          newDur = MAX_DUR;
        }
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

    // 포인터가 가장자리 영역에 있으면 스크롤 방향(1=아래, -1=위), 아니면 0
    const edgeDir = () => {
      if (!el) return 0;
      const r = el.getBoundingClientRect();
      if (lastClientY > r.bottom - EDGE) return 1;
      if (lastClientY < r.top + EDGE) return -1;
      return 0;
    };

    // 마우스가 멈춰 있어도 가장자리에선 계속 스크롤하며 크기를 갱신한다.
    const tick = () => {
      const dir = edgeDir();
      if (dir === 0) {
        raf = null;
        return;
      }
      const before = el.scrollTop;
      el.scrollTop += dir * SPEED;
      if (el.scrollTop !== before) apply(lastClientY);
      raf = requestAnimationFrame(tick);
    };

    const handleMouseMove = (e) => {
      lastClientY = e.clientY;
      apply(e.clientY);
      if (raf == null && edgeDir() !== 0) raf = requestAnimationFrame(tick);
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
      if (raf != null) cancelAnimationFrame(raf);
      window.removeEventListener("mousemove", handleMouseMove);
      window.removeEventListener("click", handleGlobalClick);
    };
  }, [resizingState, persistResize, setItems]);

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
        const poolIds = pool.filter((id) => id !== activeIdLocal);
        let insertIndex = poolIds.length;
        if (poolDOMRef.current) {
          const cardEls = Array.from(
            poolDOMRef.current.querySelectorAll("[data-pool-id]"),
          ).filter((el) => el.getAttribute("data-pool-id") !== activeIdLocal);
          let closestDist = Infinity,
            closestId = null,
            closestIsAfter = true;

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
            }
          });
          if (closestId)
            insertIndex = closestIsAfter
              ? poolIds.indexOf(closestId) + 1
              : poolIds.indexOf(closestId);
          return { region: "pool", insertIndex };
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
        // 아래쪽 하한은 축의 시작(timelineStart)뿐이다 — 이미 놓인 블록 위에
        // 겹쳐 놓는 것은 드롭이 아니라 겹침 해소(resolveOverlaps)가 막는다.
        // 그쪽은 보드 전체를 오프셋 순으로 훑으며 앞 블록의 끝(lastEnd)까지
        // 밀어내므로 Day 경계를 알 필요가 없다.
        dropMins = Math.max(
          timelineStart,
          Math.min(dropMins, timelineEnd - SNAP),
        );

        // 자석 스냅 — 이웃 블록 가장자리에 아주 가까우면 딱 붙인다(빈틈 0). 손으로
        // 정확히 맞추기 어려운 걸 돕는다. 임계값 밖이면 원래 위치 그대로.
        const SNAP_MAGNET = 5; // 분 (이 안이면 스냅) — 의도치 않게 붙는 경우를 줄임
        let bestSnap = null;
        // 축이 전 기간 연속이라 스냅도 배치된 모든 블록을 본다 — Day 로 한정하면
        // 경계 근처(23:58 드롭인데 이웃이 다음 날 00:05)에 스냅이 안 걸린다.
        board.forEach((id) => {
          if (id === activeIdLocal) return;
          const b = items[id];
          if (b?.startMins == null) return;
          const bEnd = b.startMins + (b.dur || 0);
          // 내 위를 이웃 끝에(그 밑에 붙기), 내 아래를 이웃 시작에(그 위에 붙기)
          [bEnd, b.startMins - dur].forEach((cand) => {
            const dist = Math.abs(dropMins - cand);
            if (dist <= SNAP_MAGNET && (!bestSnap || dist < bestSnap.dist)) {
              bestSnap = { mins: cand, dist };
            }
          });
        });
        if (bestSnap) {
          dropMins = Math.max(
            timelineStart,
            Math.min(bestSnap.mins, timelineEnd - SNAP),
          );
        }
        return { region: "timeline", dropMins, dur };
      }

      // 후보 목록·타임라인 어느 쪽도 아니면 "보드 밖" — 놓으면 삭제다.
      // 별도 휴지통 영역 대신 이 판정을 쓴다(후보 목록이 그만큼 넓어진다).
      // 단 검색 결과는 아직 블록이 아니라 지울 대상이 없다 — 그냥 취소한다.
      // 검색·챗봇 추천 카드는 아직 블록이 아니라 지울 대상이 없다 — 후보 밖에 놓으면
      // 삭제가 아니라 그냥 취소다("삭제됩니다" 경고를 띄우지 않는다)
      if (
        active.data?.current?.from === "search" ||
        active.data?.current?.from === "chatbot"
      )
        return { region: null };
      return { region: "discard" };
    },
    [pool, items, timelineStart, timelineEnd, board],
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
  const clearDragState = () => {
    setActiveId(null);
    setActiveDragMeta(null);
    activeDragRef.current = null;
    setDragPreview(null);
  };
  const handleDragCancel = () => {
    clearDragState();
  };

  const handleDragEnd = (event) => {
    const { active } = event;
    const activeIdLocal = active.id;
    const isFromPool = pool.includes(activeIdLocal);
    const isFromSearch = active.data.current?.from === "search";
    const isFromChatbot = active.data.current?.from === "chatbot";
    const target = computeDropTarget(active);

    clearDragState();

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
          const created = await blockApi.createBlockWithDetail(projectId, {
            ...newBlock,
            startMins: null, // 후보(POOL)로 생성된다
            orderKey,
          });
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

  // 눈금은 30분 간격이되 여행 전체가 아니라 **렌더 창**만 덮는다 — 30일 여행의
  // 1441개가 Day±1 의 145개로 준다. 좌표계는 여전히 여행 전체이고(top 식이 그대로
  // (t - timelineStart) * PX 다), 창 밖 눈금은 스크롤이 그 Day 로 가면 생긴다.
  // 새벽 빈 공간은 아래 최초 스크롤·탭 점프가 첫 블록 위치로 건너뛴다.
  // 마지막 창의 위 경계는 Infinity 라(마지막 자정 너머로 밀린 카드를 담기 위함)
  // 눈금만 축의 끝에서 자른다 — 마지막 자정 너머엔 그릴 Day 가 없다.
  const tickEnd = Math.min(windowEnd, timelineEnd);
  const timeSlots = [];
  for (let t = windowStart; t <= tickEnd; t += 30) timeSlots.push(t);

  // Day 네비게이션(스크롤→활성 Day 파생 + 탭 클릭→점프)은 useDayNav 가 소유한다.
  // scrolledDay/activeDay state 는 여기 남는다 — activeDay 가 timelineDOMRef 가
  // 만들어지기 전 렌더 초반에 이미 파생·소비되기 때문(훅은 그 뒤에서 호출된다).
  const { jumpToDay, scheduleDominantDay } = useDayNav({
    setScrolledDay,
    activeDay,
    dayKeys,
    board,
    items,
    timelineStart,
    timelineEnd,
    timelineDOMRef,
  });

  // 보드를 열면 첫 Day 의 첫 블록이 보이게 한 번만 맞춘다(부드럽게 갈 이유가 없어
  // 즉시). 그 뒤로 스크롤 위치를 건드리는 것은 탭 점프뿐이다 — board/items 가
  // 바뀔 때마다 스크롤을 뺏으면 편집 중에 화면이 튄다.
  const didInitialScrollRef = useRef(false);
  // 열람 모드는 이 컨테이너를 통째로 내린다. 돌아오면 새 엘리먼트라 scrollTop 이 0 인데
  // 활성 Day 는 떠나기 전 값 그대로다 — 맞춰주지 않으면 창이 그 Day 앞뒤에 머물러
  // 화면 맨 위엔 눈금도 카드도 없고, presence 는 팀원에게 있지도 않은 Day 를 방송한다.
  useEffect(() => {
    if (viewMode !== "edit") didInitialScrollRef.current = false;
  }, [viewMode]);
  useEffect(() => {
    if (status !== "loaded" || didInitialScrollRef.current) return;
    const el = timelineDOMRef.current;
    if (!el) return;
    didInitialScrollRef.current = true;
    // 활성 Day 로 맞춘다 — 처음 열 때는 첫 Day 라 예전과 같고, 열람 모드에서
    // 돌아올 때는 떠난 자리로 되돌아와 활성 Day 와 화면이 일치한다.
    const base = (dayNoOf(activeDay) - 1) * blockApi.MINUTES_PER_DAY;
    const target = firstStartOf(board, items, activeDay) ?? base;
    el.scrollTop = Math.max(0, (target - timelineStart - 15) * PX);
  }, [status, viewMode, activeDay, board, items, timelineStart]);

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

  // 없는 프로젝트·비멤버·잘못된 URL 이면 자동 이동 대신 같은 화면에서 안내한다.
  // (반드시 아래 "!loaded → null" 보다 앞에 둔다 — 안 그러면 에러가 null 로
  //  삼켜져 빈 화면이 된다.)
  if (status === "error") {
    return (
      <div className="epage">
        <EmptyState
          img={notFoundImg}
          title="프로젝트를 열 수 없어요"
          desc="없거나 접근 권한이 없는 프로젝트예요. 그룹에서 다시 들어와 주세요."
          action={
            <div className="epage__actions">
              <button
                type="button"
                className="btn btn-acc"
                onClick={() => navigate(ROUTES.group(groupId))}
              >
                그룹으로
              </button>
              <button
                type="button"
                className="btn btn-gh"
                onClick={() => window.location.reload()}
              >
                다시 시도
              </button>
            </div>
          }
        />
      </div>
    );
  }

  // 스냅샷이 시드되기 전(로딩 중)에는 보드를 그리지 않는다(전역 스피너가 알린다).
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
          { label: "개인 페이지", to: ROUTES.my },
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
                {/* 목록만 따로 스크롤 컨테이너로 감싼다(.day-list) — 여행이 길어도
                    컬럼이 페이지를 늘리지 않고 이 안에서만 스크롤된다. 활성 탭이
                    그 안에서 밀려나면 activeTabRef 가 다시 끌어온다. 활성 Day 는
                    스크롤에서 파생되므로 축을 굴리면 탭 목록도 함께 따라온다. */}
                <div className="day-list">
                  {dayKeys.map((day, i) => (
                    <DayTab
                      key={day}
                      ref={activeDay === day ? activeTabRef : null}
                      label={`Day ${i + 1}`}
                      date={dayDate(project, i, "short")}
                      // Day 별 목록은 상태가 아니라 보드에서 뽑는다(blocksOfDay).
                      count={blocksOfDay(board, items, day).length}
                      // 탭은 이제 화면을 갈아끼우지 않는다 — 그 Day 로 스크롤할 뿐이고,
                      // 하이라이트도 스크롤이 정한 Day(activeDay)를 따라간다.
                      isActive={activeDay === day}
                      onClick={() => jumpToDay(day)}
                      viewers={dayViewersOf(day)}
                    />
                  ))}
                </div>
              </div>

              <div className="main">
                <div className="board plan-board">
                  <div className="bd-head">
                    {/* Day 표시는 여기 하나뿐이다. 축 안에 라벨을 같이 두면 스크롤
                        내내 카드 위를 가로질러 오히려 축을 토막 내 보이게 했다.
                        활성 Day 가 스크롤에서 파생되므로 이 제목도 스크롤을 따라
                        바뀐다 — 고정 표시가 아니라 "지금 보고 있는 Day" 다.
                        (날짜는 표시 전용. 여행 기간은 상단바 제목 옆 ✎ 에서 바꾼다) */}
                    <h2>Day {activeDayIndex + 1}</h2>
                    <span className="date">
                      {dayDate(project, activeDayIndex) || "날짜 미정"}
                    </span>
                    <HintIcon
                      label="계획표 사용 안내"
                      tip="후보 블록을 원하는 시간에 끌어다 놓아 일정을 만들어요. 블록의 위·아래 가장자리를 누르면 원하는 만큼 길이를 조절하고, 블록 사이 🚗 버튼으로 이동수단을 추가할 수 있어요. 여행 날짜는 위 제목 옆 ✎ 에서 바꿀 수 있어요."
                    />
                    <div className="right">
                      <button
                        className="auto-transport-btn"
                        onClick={() => regenerateAutoTransport(activeDay)}
                        disabled={
                          isGeneratingTransport ||
                          blocksOfDay(board, items, activeDay).filter(
                            (id) => !items[id]?.auto && isServerBlock(id),
                          ).length < 2
                        }
                      >
                        {isGeneratingTransport
                          ? "생성 중..."
                          : `🚗 Day ${dayNoOf(activeDay)} 이동수단 생성`}
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
                              ? "밤 12시"
                              : fmtTimeLong(t)}
                          </span>
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
                            {fmtTime(dragPreview.dropMins)}에 놓기
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
                        // 창 밖 카드는 DOM 에 올리지 않는다. 목록(boardItems) 자체는
                        // 자르지 않는다 — 아래 boundTop 과 "다음 항목", 그리고
                        // 컨테이너 높이(contentEnd)가 모두 보드 전체에서의 앞뒤를
                        // 봐야 맞는다. 목록을 자르면 창 첫 카드의 boundTop 이 축
                        // 시작으로 풀려 창 밖 이웃을 뚫고 리사이즈된다. 자르는 것은
                        // 렌더뿐이고 상태·저장·겹침 해소가 보는 것은 그대로다.
                        // 걸치기만 해도 그린다(자정을 넘어 창으로 들어오는 블록).
                        // 지금 조작 중인 카드는 창 밖이어도 남긴다 — 끌고 있는
                        // 카드(activeId)는 먼 Day 로 끌어가는 동안, 크기를 잡고
                        // 있는 카드(resizingState)는 그 사이 휠로 하루를 넘겨도
                        // 손에서 사라지면 안 된다. 리사이즈는 window 레벨
                        // mousemove 라 언마운트돼도 상태·저장은 살지만, 잡고 있는
                        // 것이 눈앞에서 없어지는 것 자체가 결함이다.
                        const inWindow =
                          data.endMins > windowStart &&
                          data.startMins < windowEnd;
                        if (
                          !inWindow &&
                          data.id !== activeId &&
                          data.id !== resizingState?.id
                        )
                          return null;

                        // 위 모서리를 끌어올릴 수 있는 한계 — 앞 카드의 끝이다.
                        // 목록이 보드 전체라 자정을 넘어온 블록도 그냥 앞 카드로
                        // 여기 들어 있다. 맨 앞 카드는 여행 전체에서 가장 이른
                        // 블록이라 축의 시작 말고는 막을 것이 없다.
                        // 이 값이 리사이즈 핸들러의 유일한 하한이라(resizingState.
                        // boundTop) 여기만 올리면 미리보기와 확정이 갈라지지 않는다.
                        const boundTop =
                          index > 0
                            ? boardItems[index - 1].endMins
                            : timelineStart;

                        const nextData = boardItems[index + 1];

                        // 두 일정 사이의 빈 시간(gap). 이 빈 구간 자체가 "이동 추가"
                        // 버튼이 된다 — 높이 = gap 크기라 좁아져도 오른쪽으로 밀리지
                        // 않고, 너무 좁으면 최소 높이만큼 경계선에 걸쳐 클릭 여지를 남긴다.
                        const gapMins = nextData
                          ? nextData.startMins - data.endMins
                          : 0;
                        const gapPx = gapMins * PX;
                        const gapZoneH = Math.max(gapPx, TRANS_GAP_MIN_PX);
                        const gapZoneTop =
                          (data.endMins - timelineStart) * PX -
                          (gapZoneH - gapPx) / 2;
                        // 존이 앉는 자리는 앞 카드의 끝이다. 축이 여행 전체라 그
                        // 자리가 렌더 창 밖일 수 있어(눈금도 없는 빈 구간) 그때는
                        // 내지 않는다 — 스크롤이 그리로 가면 창이 따라가며 다시 나온다.
                        const showGapBtn =
                          nextData &&
                          data.item.cat !== "trans" &&
                          nextData.item.cat !== "trans" &&
                          data.endMins >= windowStart &&
                          data.endMins <= windowEnd;

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
                                onCopy={handleCopyBlock}
                                lockedBy={lockBadgeOf(data.id)}
                                editor={editorBadgeOf(data.id)}
                              />
                            )}

                            {/* 두 블록 사이 빈 시간 = "이동 추가" 존. 갭 전체를 은은한
                                빗금 버튼으로 채우고, hover 하면 빗금이 진해지며 라벨이
                                뜬다. 높이가 gap 크기라 좁아져도 밀리지 않는다. */}
                            {showGapBtn && !isThisActiveTimelineCard && (
                              <button
                                type="button"
                                className="trans-gap"
                                title="이동 추가"
                                style={{
                                  top: `${gapZoneTop}px`,
                                  height: `${gapZoneH}px`,
                                }}
                                onClick={(e) => {
                                  e.stopPropagation();
                                  // 버튼이 보드 전체에 깔리므로 Day 는 활성 탭이
                                  // 아니라 앞 블록이 실제로 앉은 Day 다 —
                                  // 활성 Day 를 넘기면 다른 Day 의 버튼이
                                  // "그 Day 에 없는 블록"으로 조용히 튕긴다.
                                  handleAddSingleTransport(
                                    `d${blockApi.dayNoOfOffset(data.startMins)}`,
                                    data.id,
                                    nextData.id,
                                  );
                                }}
                              >
                                <span className="trans-gap-label">
                                  <span className="trans-gap-ico">🚗</span>
                                  이동 추가
                                </span>
                              </button>
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

{/* 다른 멤버들의 라이브 커서(타임라인 정밀 좌표) — 카드 위에 뜨되
                        클릭은 통과시킨다(pointer-events: none). 상태는 레이어가 자체 보유 */}
                    <RemoteCursorLayer
                      mode="tl"
                      register={registerTlCursorHandler}
                      myId={currentUser?.id}
                      timelineStart={timelineStart}
                      px={PX}
                      padTop={TL_PAD_TOP}
                      padLeft={TL_PAD_LEFT}
                      nicknameOf={nicknameOf}
                    />
                  </div>
                </div>

                <PoolPanel
                  dragPreview={dragPreview}
                  poolFilterActive={poolFilterActive}
                  visiblePool={visiblePool}
                  poolCat={poolCat}
                  setPoolCat={setPoolCat}
                  poolQuery={poolQuery}
                  setPoolQuery={setPoolQuery}
                  handleCreateCustomBlock={handleCreateCustomBlock}
                  openBlockDetail={openBlockDetail}
                  handleCopyBlock={handleCopyBlock}
                  lockBadgeOf={lockBadgeOf}
                  editorBadgeOf={editorBadgeOf}
                  isDraggingFromPool={isDraggingFromPool}
                  setPoolRef={setPoolRef}
                />
              </div>

              <div className="side">
                {/* 예산+지도를 한 묶음으로 — 높이를 계획표에 맞추고 지도가 남는
                    공간을 채워, 두 카드의 아랫줄이 계획표 아랫줄과 맞는다. */}
                <div className="side-top">
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
                </div>

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
              <DragOverlayPreview
                activeId={activeId}
                draggedItem={draggedItem}
                isDraggingFromPool={isDraggingFromPool}
                isDraggingFromSearch={isDraggingFromSearch}
              />

              {/* 타임라인 밖(후보·사이드 등)의 라이브 커서 — 페이지 비율 좌표.
                  후보 목록·사이드는 Day 와 무관하게 모두가 같은 것을 보는 영역이라
                  보고 있는 Day 로 거르지 않는다 */}
              <RemoteCursorLayer
                mode="page"
                register={registerPageCursorHandler}
                myId={currentUser?.id}
                nicknameOf={nicknameOf}
              />

              {/* 챗봇 — 추천 카드를 후보 목록으로 드래그해야 하므로 반드시
                  이 DndContext 안에서 렌더한다 (위치는 fixed 라 화면상 그대로) */}
              {/* 보고 있는 Day 를 같이 넘긴다 — "점심 먹은 데" 처럼 일정을 가리키는 말이
                  여러 날에 걸릴 때 서버가 되묻지 않고 이 Day 의 블록을 고른다 */}
              <ChatbotWidget
                projectId={projectId}
                getMapBounds={getMapBounds}
                dayNo={dayNoOf(activeDay)}
                focusPlace={focusPlace}
              />
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

      <BlockEditModal
        pinPickMode={pinPickMode}
        handleCancelEdit={handleCancelEdit}
        lockBadgeOf={lockBadgeOf}
        pinnedLocation={pinnedLocation}
        handleRequestPinPick={handleRequestPinPick}
        handleSaveBlock={handleSaveBlock}
        handleReselectTransport={handleReselectTransport}
      />

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
