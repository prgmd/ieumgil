import { useEffect, useRef, useState } from "react";
import { useDraggable } from "@dnd-kit/core";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm"; // 표·취소선 등 GFM 확장 — 표는 기본 문법에 없다
import {
  sendChatbotMessage,
  fetchChatbotHistory,
  CAT_FROM_SERVER,
} from "../../../features/dashboard/api/dashboardApi";
import chatbotOpen from "../../../assets/img/chatbot_open.png";
import chatbotClose from "../../../assets/img/chatbot_close.png";
import iumiChar from "../../../assets/img/greeting.png";
import "./ChatbotWidget.css";

const CAT_LABEL = {
  SPOT: "명소/활동",
  FOOD: "식당",
  STAY: "숙소",
  ETC: "기타",
  TRANSPORT: "교통",
};

// 모드별 예시 프롬프트 — intro 시점(대화 시작 전)에만 노출되는 칩.
// 클릭하면 입력창에 텍스트만 채우고 전송은 하지 않는다(사용자가 수정/확인 후 전송).
const GENERAL_CHIPS = [
  "부산 가볼만한 곳 추천",
  "이번 주 근처 축제 알려줘",
  "서울에서 부산 기차 시간표",
  "여기서 숙소까지 택시비",
  "지금 일정 요약해줘",
];
const MAP_CHIPS = ["이 근처 카페 추천", "주변 관광지 알려줘"];

/**
 * 드래그해서 후보 목록으로 옮기는 추천 카드 — 카카오 검색 결과(SearchResultDraggable)와
 * 같은 문법. 드롭 처리(블록 생성)는 대시보드의 handleDragEnd 가 from:"chatbot" 으로
 * 분기한다. 그래서 이 위젯은 반드시 보드와 같은 DndContext 안에서 렌더돼야 한다.
 */
function CandidateDraggable({ dragId, candidate, onClick }) {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: dragId,
    data: { from: "chatbot", candidate },
  });
  return (
    <div
      ref={setNodeRef}
      className={`cbw-cand ${isDragging ? "is-dragging" : ""}`}
      {...attributes}
      {...listeners}
      onClick={() => onClick?.(candidate)}
    >
      <div className="cbw-cand-main">
        <b>{candidate.name}</b>
        <span className="cbw-cand-meta">
          {[CAT_LABEL[candidate.category] ?? candidate.category, candidate.subCategory]
            .filter(Boolean)
            .join(" · ")}
        </span>
        {candidate.address && (
          <span className="cbw-cand-addr">📍 {candidate.address}</span>
        )}
        {candidate.eventStartDate && (
          <span className="cbw-cand-addr">
            🗓 {candidate.eventStartDate}
            {candidate.eventEndDate ? ` ~ ${candidate.eventEndDate}` : ""}
          </span>
        )}
      </div>
      {/* 끌어다 놓기 유도용 손잡이 — 검색 결과(.sr-grip)와 같은 글리프 */}
      <div className="cbw-cand-grip">⠿</div>
    </div>
  );
}

/**
 * AI 챗봇 (이음이) — 서버가 Claude 로 중계하고, 추천 결과(candidates)는
 * 그대로 후보 블록으로 만들 수 있는 형태로 온다.
 *
 * 탭 = 대화 모드:
 * - 일반 채팅(GENERAL): 프로젝트 맥락의 일반 추천
 * - 지도 기반 추천(MAP): 지금 보이는 지도 범위(뷰포트) 안에서 추천 —
 *   전송 시점의 지도 사각형을 getMapBounds 로 받아 함께 보낸다
 *
 * 대화 히스토리는 서버가 프로젝트+멤버 단위로 유지하므로 여기 상태는 표시용이다
 * (위젯을 닫았다 열어도 대화 맥락은 서버에 남아 이어진다).
 *
 * @param {number} projectId
 * @param {() => ({swLat,swLng,neLat,neLng}|null)} getMapBounds 지도 뷰포트 (지도 미준비면 null)
 * @param {(item: object) => void} [focusPlace] 추천 카드 클릭 시 사이드 지도를 그 장소로 옮기고 말풍선 표시
 * @param {number} dayNo 지금 보고 있는 Day 번호(1부터) — "점심 먹은 데" 처럼 일정을 가리키는
 *        말이 여러 날에 걸릴 때 서버가 이 Day 의 블록을 고른다
 */
export function ChatbotWidget({ projectId, getMapBounds, dayNo, focusPlace }) {
  const [isOpen, setIsOpen] = useState(false);
  const [activeTab, setActiveTab] = useState("general");
  const [inputValue, setInputValue] = useState("");
  const [messages, setMessages] = useState([]); // {id, role: "user"|"bot"|"error", text, candidates?}
  const [sending, setSending] = useState(false);
  const bodyRef = useRef(null);
  const msgSeqRef = useRef(0);
  const inputRef = useRef(null);

  // ── 로고 버튼 드래그 이동 (QA 배치3) — 기본은 우하단, 끌어서 어디든 ──
  // null = 기본 위치(CSS 의 right/bottom). 드래그하면 {left, top} 픽셀 고정.
  // 6px 이상 움직였을 때만 드래그로 판정해, 릴리즈 때 따라오는 click(토글)을 삼킨다.
  const [fabPos, setFabPos] = useState(null);
  const suppressToggleRef = useRef(false);

  const handleFabPointerDown = (e) => {
    // 새 제스처 시작 — 이전 드래그가 pointercancel 등으로 click 없이 끝나
    // suppressToggleRef 가 true 로 남아있었다면 여기서 초기화한다.
    suppressToggleRef.current = false;

    const start = {
      x: e.clientX,
      y: e.clientY,
      rect: e.currentTarget.getBoundingClientRect(),
      moved: false,
    };
    const onMove = (ev) => {
      const dx = ev.clientX - start.x;
      const dy = ev.clientY - start.y;
      if (!start.moved && Math.hypot(dx, dy) < 6) return;
      start.moved = true;
      setFabPos({
        left: Math.min(
          Math.max(8, start.rect.left + dx),
          window.innerWidth - start.rect.width - 8,
        ),
        top: Math.min(
          Math.max(8, start.rect.top + dy),
          window.innerHeight - start.rect.height - 8,
        ),
      });
    };
    // 브라우저가 터치 스크롤/제스처 가로채기 등으로 pointercancel 을 쏘면
    // pointerup 없이 제스처가 끝난다 — cleanup 을 못 하면 리스너가 계속 붙어
    // 있어 이후의 단순 hover 에서도 onMove 가 불려 FAB 가 커서를 따라다닌다.
    const cleanup = () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onCancel);
    };
    const onUp = () => {
      cleanup();
      if (start.moved) suppressToggleRef.current = true;
    };
    const onCancel = () => {
      cleanup();
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onCancel);
  };

  const handleFabClick = () => {
    if (suppressToggleRef.current) {
      suppressToggleRef.current = false; // 드래그 릴리즈 — 열림/닫힘을 바꾸지 않는다
      return;
    }
    setIsOpen((v) => !v);
  };

  // 패널은 로고 버튼 위에 얹힌다 — 버튼이 이동했으면 그 위치 기준으로 따라간다
  const fabStyle = fabPos
    ? { left: fabPos.left, top: fabPos.top, right: "auto", bottom: "auto" }
    : undefined;
  const panelStyle = fabPos
    ? {
        // 패널 폭(~340)을 넘겨 왼쪽으로 밀리지 않게 상한을 둔다(FAB를 왼쪽 끝으로
        // 끌어도 패널이 화면 밖으로 잘리지 않는다)
        right: Math.min(
          Math.max(8, window.innerWidth - fabPos.left - 60),
          window.innerWidth - 348,
        ),
        bottom: Math.min(
          Math.max(8, window.innerHeight - fabPos.top + 10),
          window.innerHeight - 540, // 패널(~530px)이 화면 위로 밀려나지 않게
        ),
      }
    : undefined;

  // 새 메시지·"생각 중" 표시가 생기면 대화창을 맨 아래로
  useEffect(() => {
    const el = bodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, sending, isOpen]);

  // 마운트 시 서버 이력을 불러 말풍선을 복원한다. 이미 보낸 메시지가 있으면(레이스) 덮지 않는다.
  useEffect(() => {
    let cancelled = false;
    fetchChatbotHistory(projectId)
      .then((res) => {
        if (cancelled || !res?.turns?.length) return;
        const restored = res.turns.map((t) => {
          msgSeqRef.current += 1;
          return {
            id: msgSeqRef.current,
            role: t.role === "assistant" ? "bot" : "user",
            text: t.content,
            candidates: t.candidates ?? [],
          };
        });
        setMessages((prev) => (prev.length ? prev : restored));
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  // 추천 카드 클릭 → 사이드 지도로 focus + 블록 핀과 같은 커스텀 말풍선.
  // candidate 를 blockOverlayInner 가 아는 item 모양으로 어댑트한다(드롭 분기와 동일 매핑).
  // 좌표 없는 후보(교통 등)는 focusPlace 가 내부에서 무시한다.
  const handleCandidateClick = (candidate) => {
    focusPlace?.({
      cat: CAT_FROM_SERVER[candidate.category] ?? "etc",
      name: candidate.name,
      detail: candidate.detail || candidate.address,
      source: candidate.source ?? "BOT",
      placeId: candidate.placeId != null ? String(candidate.placeId) : null,
      lat: candidate.lat,
      lng: candidate.lng,
    });
  };

  const pushMessage = (msg) => {
    msgSeqRef.current += 1;
    setMessages((prev) => [...prev, { id: msgSeqRef.current, ...msg }]);
  };

  const handleSend = async () => {
    const message = inputValue.trim();
    if (!message || sending) return;

    // 모드는 전송 시점의 탭이 정한다. MAP 은 지도 뷰포트가 필수 —
    // 지도가 아직 없으면(로딩 전) 보내지 않고 안내한다.
    const mode = activeTab === "map" ? "MAP" : "GENERAL";
    let mapContext;
    if (mode === "MAP") {
      mapContext = getMapBounds?.();
      if (!mapContext) {
        pushMessage({
          role: "error",
          text: "지도가 아직 준비되지 않았어요. 오른쪽 지도가 보이는 상태에서 다시 시도해주세요.",
        });
        return;
      }
    }

    setInputValue("");
    pushMessage({ role: "user", text: message });
    setSending(true);
    try {
      const result = await sendChatbotMessage(projectId, {
        message,
        mode,
        mapContext,
        dayNo,
      });
      pushMessage({
        role: "bot",
        text: result?.reply ?? "…",
        candidates: result?.candidates ?? [],
      });
    } catch (e) {
      // 서버 응답 본문(CustomResponse)일 때만 그 메시지를 쓴다 — 타임아웃·네트워크
      // 오류는 axios 원문("timeout of 30000ms exceeded")이라 그대로 노출하지 않는다.
      const fallback = "응답을 받지 못했어요. 잠시 후 다시 시도해주세요.";
      pushMessage({
        role: "error",
        text: e?.code ? (e.message ?? fallback) : fallback,
      });
      setInputValue(message); // 실패 시 입력을 되살려 재타이핑을 없앤다
    } finally {
      setSending(false);
    }
  };

  return (
    <>
      {/* 로고 버튼은 항상 같은 자리에 남는다(QA 배치2) — 열려 있어도 사라지지
          않고, 같은 위치를 다시 눌러 닫는다. 열림 상태에선 닫기 아이콘으로 바뀐다.
          꾹 잡고 끌면 원하는 곳으로 옮길 수 있다(QA 배치3). */}
      <button
        className={`cbw-fab ${isOpen ? "is-open" : ""}`}
        style={fabStyle}
        onPointerDown={handleFabPointerDown}
        onClick={handleFabClick}
        title={isOpen ? "이음이 닫기" : "이음이 열기 · 끌어서 이동"}
      >
        <img
          src={isOpen ? chatbotClose : chatbotOpen}
          alt={isOpen ? "이음이 닫기" : "챗봇 이음이"}
          className="cbw-fab-icon"
        />
      </button>

      {isOpen && (
        <div className="cbw" style={panelStyle}>
          <div className="cbw-head">
            <span className="cbw-head-title">
              <img src={chatbotClose} alt="" className="cbw-head-icon" /> 챗봇 이음이
            </span>
            <button className="cbw-close" onClick={() => setIsOpen(false)}>
              ✕
            </button>
          </div>

          <div className="cbw-tabs">
            <button
              className={`cbw-tab ${activeTab === "general" ? "on" : ""}`}
              onClick={() => setActiveTab("general")}
              title="장소·축제·교통·일정 무엇이든 물어보세요"
            >
              💬 일반 채팅
            </button>
            <button
              className={`cbw-tab ${activeTab === "map" ? "on" : ""}`}
              onClick={() => setActiveTab("map")}
              title="현재 지도 범위에서 장소를 추천받아요"
            >
              🗺️ 지도 기반 추천
            </button>
          </div>

          <div className="cbw-body" ref={bodyRef}>
            {/* 대화 시작 전(intro)에만 캐릭터로 인사 — 첫 인상만 살리고
                대화가 시작되면 사라진다(스크롤 공간을 차지하지 않게). */}
            {!messages.some((m) => m.role === "user" || m.role === "bot") && (
              <div className="cbw-intro">
                <img
                  className="cbw-intro__char"
                  src={iumiChar}
                  alt=""
                  aria-hidden="true"
                />
                <span className="cbw-intro__hi">이음이가 도와드릴게요</span>
              </div>
            )}
            <div className="cbw-bubble">
              {activeTab === "map" ? (
                <>
                  지금 <b>지도에 보이는 범위 안</b>에서 장소를 추천해드려요 —
                  지도를 원하는 동네로 옮긴 뒤 물어보세요. 마음에 드는 추천을{" "}
                  <b>끌어다 후보 목록에</b> 담으세요.
                </>
              ) : (
                <>
                  안녕하세요, 이음이예요! 장소 검색, 축제 추천, 버스·기차·항공
                  시간표, 택시·도보 경로와 요금, 지금 일정 요약까지 뭐든
                  물어보세요 — 마음에 드는 추천을 <b>끌어다 후보 목록에</b>{" "}
                  담으세요.
                </>
              )}
            </div>

            {/* 예시 프롬프트 칩 — 대화가 시작되기 전(intro 시점)에만 노출.
                클릭하면 입력창에 텍스트만 채운다(전송은 사용자가 확인 후 직접). */}
            {!messages.some((m) => m.role === "user" || m.role === "bot") && (
              <div className="cbw-chips">
                {(activeTab === "map" ? MAP_CHIPS : GENERAL_CHIPS).map(
                  (chip) => (
                    <button
                      key={chip}
                      type="button"
                      className="cbw-chip"
                      onClick={() => {
                        setInputValue(chip);
                        inputRef.current?.focus();
                      }}
                    >
                      {chip}
                    </button>
                  ),
                )}
              </div>
            )}

            {messages.map((m) => (
              <div key={m.id} className="cbw-row">
                <div
                  className={`cbw-bubble ${m.role === "user" ? "is-user" : ""} ${m.role === "error" ? "is-error" : ""}`}
                >
                  {/* 봇 응답은 마크다운(목록·강조 등)으로 온다 — raw HTML 은
                      react-markdown 기본값이 걸러 주므로 그대로 안전하다 */}
                  {m.role === "bot" ? (
                    <div className="cbw-md">
                      <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        components={{
                          // 링크(지도보기 등)는 새 탭 — 같은 탭에서 열리면
                          // 대시보드를 벗어나 실시간 연결·대화가 끊긴다.
                          // node 는 react-markdown 내부용이라 DOM 에 새면 안 된다
                          a: (props) => {
                            const rest = { ...props };
                            delete rest.node;
                            return (
                              <a
                                {...rest}
                                target="_blank"
                                rel="noreferrer noopener"
                              />
                            );
                          },
                        }}
                      >
                        {m.text}
                      </ReactMarkdown>
                    </div>
                  ) : (
                    m.text
                  )}
                </div>
                {m.candidates?.length > 0 && (
                  <div className="cbw-cands">
                    <div className="cbw-cands-hint">
                      ⠿ 끌어서 후보 목록에 놓으면 담겨요
                    </div>
                    {m.candidates.map((c, i) => (
                      <CandidateDraggable
                        key={`${m.id}-${i}`}
                        dragId={`chatbot-cand-${m.id}-${i}`}
                        candidate={c}
                        onClick={handleCandidateClick}
                      />
                    ))}
                  </div>
                )}
              </div>
            ))}

            {sending && (
              <div className="cbw-bubble is-typing">
                이음이가 생각 중
                <span className="cbw-dots" aria-hidden="true">
                  <i />
                  <i />
                  <i />
                </span>
              </div>
            )}
          </div>

          <div className="cbw-foot">
            <input
              ref={inputRef}
              className="cbw-input"
              type="text"
              value={inputValue}
              maxLength={2000}
              onChange={(e) => setInputValue(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter" && !e.nativeEvent.isComposing)
                  handleSend();
              }}
              placeholder={
                activeTab === "map"
                  ? "이 지역에서 찾고 싶은 것 (예: 조용한 카페)"
                  : "키워드를 던져보세요 (예: 부산 야경)"
              }
            />
            <button
              className="cbw-send"
              onClick={handleSend}
              disabled={sending || !inputValue.trim()}
            >
              {sending ? "…" : "전송"}
            </button>
          </div>
        </div>
      )}
    </>
  );
}
