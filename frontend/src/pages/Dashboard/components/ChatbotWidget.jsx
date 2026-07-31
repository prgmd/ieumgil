import { useState } from "react";
import "./ChatbotWidget.css";

export function ChatbotWidget() {
  const [isOpen, setIsOpen] = useState(false);
  const [activeTab, setActiveTab] = useState("general");
  const [inputValue, setInputValue] = useState("");

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

          <div className="cbw-body">
            <div className="cbw-bubble">
              안녕하세요, 이음이예요! 키워드를 던지면 여기 대화창에 추천을
              보여드릴게요 — 마음에 드는 것만 <b>+ 추가</b>로 후보 목록에
              담으세요.
            </div>
          </div>

          <div className="cbw-foot">
            <input
              className="cbw-input"
              type="text"
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder="키워드를 던져보세요 (예: 부산 야경)"
            />
            <button className="cbw-send" onClick={() => setInputValue("")}>
              전송
            </button>
          </div>
        </div>
      )}
    </>
  );
}
