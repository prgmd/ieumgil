package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;

public interface ChatbotQueryService {

    /** 프로젝트+멤버의 저장된 대화 이력(후보 포함). */
    ChatbotResDTO.HistoryResult loadHistory(Long projectId, Long memberId);
}
