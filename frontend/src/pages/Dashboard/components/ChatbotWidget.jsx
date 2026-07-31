import React, { useState } from "react";

export function ChatbotWidget() {
  // 챗봇 창 열림/닫힘 상태 관리
  const [isOpen, setIsOpen] = useState(false);
  // 선택된 탭 상태 관리 ('general' 또는 'map')
  const [activeTab, setActiveTab] = useState("general");
  // 입력창 상태 관리
  const [inputValue, setInputValue] = useState("");

  return (
    <>
      {/* 💡 1. 플로팅 버튼 (챗봇 창이 닫혀있을 때만 표시) */}
      {!isOpen && (
        <button
          onClick={() => setIsOpen(true)}
          style={{
            position: "fixed",
            bottom: "30px",
            right: "30px",
            width: "60px",
            height: "60px",
            borderRadius: "50%",
            backgroundColor: "#6A4C3D", // 프로토타입의 갈색
            color: "white",
            border: "none",
            boxShadow: "0 4px 12px rgba(0,0,0,0.15)",
            cursor: "pointer",
            zIndex: 9999,
            display: "flex",
            justifyContent: "center",
            alignItems: "center",
            fontSize: "28px",
            transition: "transform 0.2s",
          }}
          onMouseOver={(e) => (e.currentTarget.style.transform = "scale(1.05)")}
          onMouseOut={(e) => (e.currentTarget.style.transform = "scale(1)")}
        >
          ✈️
        </button>
      )}

      {/* 💡 2. 챗봇 모달 창 (isOpen이 true일 때만 표시) */}
      {isOpen && (
        <div
          style={{
            position: "fixed",
            bottom: "30px",
            right: "30px",
            width: "340px",
            backgroundColor: "#fbf8f1", // 배경 베이지 톤
            borderRadius: "16px",
            boxShadow: "0 8px 24px rgba(0,0,0,0.2)",
            zIndex: 9999,
            display: "flex",
            flexDirection: "column",
            overflow: "hidden",
            fontFamily: "sans-serif",
          }}
        >
          {/* 헤더 영역 */}
          <div
            style={{
              backgroundColor: "#6A4C3D",
              color: "#fff",
              padding: "16px",
              display: "flex",
              justifyContent: "space-between",
              alignItems: "center",
            }}
          >
            <span style={{ fontWeight: "bold", fontSize: "15px" }}>
              ✈️ 챗봇 이음이
            </span>
            <button
              onClick={() => setIsOpen(false)}
              style={{
                background: "none",
                border: "none",
                color: "#fff",
                cursor: "pointer",
                fontSize: "18px",
              }}
            >
              ✕
            </button>
          </div>

          {/* 탭 버튼 영역 */}
          <div style={{ display: "flex", padding: "12px 16px 0", gap: "8px" }}>
            <button
              onClick={() => setActiveTab("general")}
              style={{
                flex: 1,
                padding: "10px",
                borderRadius: "8px 8px 0 0",
                border: "none",
                backgroundColor: activeTab === "general" ? "#8a624a" : "#fff",
                color: activeTab === "general" ? "#fff" : "#666",
                cursor: "pointer",
                fontSize: "13px",
                fontWeight: "bold",
                boxShadow:
                  activeTab === "general"
                    ? "none"
                    : "inset 0 -2px 5px rgba(0,0,0,0.05)",
              }}
            >
              💬 일반 채팅
            </button>
            <button
              onClick={() => setActiveTab("map")}
              style={{
                flex: 1,
                padding: "10px",
                borderRadius: "8px 8px 0 0",
                border: "1px solid #eee",
                borderBottom: "none",
                backgroundColor: activeTab === "map" ? "#8a624a" : "#fff",
                color: activeTab === "map" ? "#fff" : "#666",
                cursor: "pointer",
                fontSize: "13px",
                fontWeight: "bold",
              }}
            >
              🗺️ 지도 기반 추천
            </button>
          </div>

          {/* 채팅 메시지 영역 */}
          <div
            style={{
              padding: "20px 16px",
              backgroundColor: "#fbf8f1",
              minHeight: "180px",
              display: "flex",
              flexDirection: "column",
              gap: "12px",
            }}
          >
            {/* 챗봇의 말풍선 */}
            <div
              style={{
                backgroundColor: "#fff",
                padding: "14px",
                borderRadius: "0 12px 12px 12px",
                fontSize: "13px",
                color: "#333",
                lineHeight: "1.6",
                border: "1px solid #eee",
                boxShadow: "0 2px 4px rgba(0,0,0,0.02)",
                alignSelf: "flex-start",
                maxWidth: "90%",
              }}
            >
              안녕하세요, 이음이예요! 키워드를 던지면 여기 대화창에 추천을
              보여드릴게요 — 마음에 드는 것만 <b>+ 추가</b>로 후보 목록에
              담으세요.
            </div>
          </div>

          {/* 하단 입력 영역 */}
          <div
            style={{
              padding: "12px 16px",
              backgroundColor: "#fff",
              display: "flex",
              gap: "8px",
              borderTop: "1px solid #eee",
            }}
          >
            <input
              type="text"
              value={inputValue}
              onChange={(e) => setInputValue(e.target.value)}
              placeholder="키워드를 던져보세요 (예: 부산 야경)"
              style={{
                flex: 1,
                padding: "10px 14px",
                borderRadius: "20px",
                border: "1px solid #ddd",
                outline: "none",
                fontSize: "13px",
                backgroundColor: "#f9f9f9",
              }}
            />
            <button
              onClick={() => {
                setInputValue(""); /* API 통신 로직 추가 예정 */
              }}
              style={{
                backgroundColor: "#6A4C3D",
                color: "#fff",
                border: "none",
                borderRadius: "20px",
                padding: "0 18px",
                fontWeight: "bold",
                cursor: "pointer",
                fontSize: "13px",
              }}
            >
              전송
            </button>
          </div>
        </div>
      )}
    </>
  );
}
