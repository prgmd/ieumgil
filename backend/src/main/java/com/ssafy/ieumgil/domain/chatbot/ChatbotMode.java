package com.ssafy.ieumgil.domain.chatbot;

/**
 * 챗봇 대화 모드 (BOT-03).
 * GENERAL은 일반 채팅, MAP은 현재 지도 뷰포트 범위로 추천을 한정하는 지도 기반 추천 모드다.
 * MAP은 뷰포트 컨텍스트 배관이 아직 없어 현재 미지원 — 요청이 오면 CHATBOT501로 거절한다.
 */
public enum ChatbotMode {
    GENERAL,
    MAP
}
