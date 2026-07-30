package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotReqDTO;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.exception.ChatbotException;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatbotCommandServiceImplTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private ChatHistoryStore chatHistoryStore;

    private ChatbotCommandServiceImpl chatbotCommandService;

    @BeforeEach
    void setUp() {
        chatbotCommandService = new ChatbotCommandServiceImpl(chatModel, chatHistoryStore);
    }

    @Test
    void firstMessageWithNoHistoryReturnsGmsReply() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("안녕하세요! 여행 계획 도와드릴게요."));

        ChatbotResDTO.MessageResult result = chatbotCommandService.sendMessage(
                1L, 1L, new ChatbotReqDTO.SendMessage("안녕")
        );

        assertThat(result.reply()).isEqualTo("안녕하세요! 여행 계획 도와드릴게요.");
    }

    @Test
    void existingHistoryIsAppendedAfterSuccess() {
        List<ChatTurn> existing = List.of(new ChatTurn(ChatTurn.ROLE_USER, "이전 질문"));
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(existing);
        when(chatModel.call(any(Prompt.class))).thenReturn(canned("이어지는 답변"));

        chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("다음 질문"));

        verify(chatHistoryStore).appendExchange(
                eq(1L), eq(1L),
                eq(new ChatTurn(ChatTurn.ROLE_USER, "다음 질문")),
                eq(new ChatTurn(ChatTurn.ROLE_ASSISTANT, "이어지는 답변"))
        );
    }

    @Test
    void gmsFailureThrowsChatbotException() {
        when(chatHistoryStore.loadHistory(1L, 1L)).thenReturn(List.of());
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> chatbotCommandService.sendMessage(1L, 1L, new ChatbotReqDTO.SendMessage("안녕")))
                .isInstanceOf(ChatbotException.class);
    }

    private ChatResponse canned(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
