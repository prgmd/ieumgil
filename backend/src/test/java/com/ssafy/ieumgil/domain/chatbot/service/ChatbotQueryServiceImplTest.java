package com.ssafy.ieumgil.domain.chatbot.service;

import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatHistoryStore;
import com.ssafy.ieumgil.domain.chatbot.repository.ChatTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ChatbotQueryServiceImplTest {

    @Mock
    private ChatHistoryStore chatHistoryStore;

    @InjectMocks
    private ChatbotQueryServiceImpl service;

    @Test
    @DisplayName("저장된 이력을 턴별로 매핑해 준다")
    void 이력을_매핑한다() {
        given(chatHistoryStore.loadHistory(1L, 2L)).willReturn(List.of(
                new ChatTurn(ChatTurn.ROLE_USER, "제주 추천"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "성산일출봉 어때요", List.of())
        ));

        ChatbotResDTO.HistoryResult result = service.loadHistory(1L, 2L);

        assertThat(result.turns()).hasSize(2);
        assertThat(result.turns().get(0).role()).isEqualTo("user");
        assertThat(result.turns().get(1).content()).isEqualTo("성산일출봉 어때요");
    }
}
