import { useEffect, useRef, useState } from "react";
import { useDraggable } from "@dnd-kit/core";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm"; // 표·취소선 등 GFM 확장 — 표는 기본 문법에 없다
import { sendChatbotMessage } from "../../../features/dashboard/api/dashboardApi";
import "./ChatbotWidget.css";

const CAT_LABEL = {
  SPOT: "명소/활동",
  FOOD: "식당",
  STAY: "숙소",
  ETC: "기타",
  TRANSPORT: "교통",
};

/**
 * 드래그해서 후보 목록으로 옮기는 추천 카드 — 카카오 검색 결과(SearchResultDraggable)와
 * 같은 문법. 드롭 처리(블록 생성)는 대시보드의 handleDragEnd 가 from:"chatbot" 으로
 * 분기한다. 그래서 이 위젯은 반드시 보드와 같은 DndContext 안에서 렌더돼야 한다.
 */
function CandidateDraggable({ dragId, candidate }) {
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
 */
export function ChatbotWidget({ projectId, getMapBounds }) {
  const [isOpen, setIsOpen] = useState(false);
  const [activeTab, setActiveTab] = useState("general");
  const [inputValue, setInputValue] = useState("");
  const [messages, setMessages] = useState([]); // {id, role: "user"|"bot"|"error", text, candidates?}
  const [sending, setSending] = useState(false);
  const bodyRef = useRef(null);
  const msgSeqRef = useRef(0);

  // 새 메시지·"생각 중" 표시가 생기면 대화창을 맨 아래로
  useEffect(() => {
    const el = bodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, sending, isOpen]);

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
      });
      pushMessage({
        role: "bot",
        text: result?.reply ?? "…",
        candidates: result?.candidates ?? [],
      });
    } catch (e) {
      pushMessage({
        role: "error",
        text: e?.message ?? "응답을 받지 못했어요. 잠시 후 다시 시도해주세요.",
      });
    } finally {
      setSending(false);
    }
  };

  return (
    <>
      {!isOpen && (
        <button className="cbw-fab" onClick={() => setIsOpen(true)}>
          ✈️
        </button>
      )}

      {isOpen && (
        <div className="cbw">
          <div className="cbw-head">
            <span className="cbw-head-title">✈️ 챗봇 이음이</span>
            <button className="cbw-close" onClick={() => setIsOpen(false)}>
              ✕
            </button>
          </div>

          <div className="cbw-tabs">
            <button
              className={`cbw-tab ${activeTab === "general" ? "on" : ""}`}
              onClick={() => setActiveTab("general")}
            >
              💬 일반 채팅
            </button>
            <button
              className={`cbw-tab ${activeTab === "map" ? "on" : ""}`}
              onClick={() => setActiveTab("map")}
            >
              🗺️ 지도 기반 추천
            </button>
          </div>

          <div className="cbw-body" ref={bodyRef}>
            <div className="cbw-bubble">
              {activeTab === "map" ? (
                <>
                  지금 <b>지도에 보이는 범위 안</b>에서 추천해드려요 — 지도를
                  원하는 동네로 옮긴 뒤 물어보세요.
                </>
              ) : (
                <>
                  안녕하세요, 이음이예요! 여행지·맛집·일정 뭐든 물어보세요 —
                  마음에 드는 추천을 <b>끌어다 후보 목록에</b> 담으세요.
                </>
              )}
            </div>

            {messages.map((m) => (
              <div key={m.id}>
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
                      />
                    ))}
                  </div>
                )}
              </div>
            ))}

            {sending && (
              <div className="cbw-bubble is-typing">이음이가 생각 중…</div>
            )}
          </div>

          <div className="cbw-foot">
            <input
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
