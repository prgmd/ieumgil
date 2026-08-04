package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatbotQueryServiceImpl implements ChatbotQueryService {

    private final ChatHistoryStore chatHistoryStore;

    @Override
    public ChatbotResDTO.HistoryResult loadHistory(Long projectId, Long memberId) {
        return new ChatbotResDTO.HistoryResult(
                chatHistoryStore.loadHistory(projectId, memberId).stream()
                        .map(t -> new ChatbotResDTO.HistoryTurn(t.role(), t.content(), t.candidates()))
                        .toList());
    }
}
