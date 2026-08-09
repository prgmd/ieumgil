package com.ssafy.ieumgil.domain.chatbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.chatbot.dto.ChatbotResDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTurnTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("2-인자 생성자는 candidates를 빈 배열로 둔다")
    void 유저턴은_후보가_없다() {
        ChatTurn turn = new ChatTurn(ChatTurn.ROLE_USER, "안녕");
        assertThat(turn.candidates()).isEmpty();
    }

    @Test
    @DisplayName("candidates를 담아 직렬화·역직렬화해도 살아남는다")
    void 후보가_왕복에서_보존된다() throws Exception {
        ChatbotResDTO.Candidate c = ChatbotResDTO.Candidate.builder()
                .name("성산일출봉").source(BlockSource.KAKAO).placeId("123").build();
        ChatTurn turn = new ChatTurn(ChatTurn.ROLE_ASSISTANT, "추천해요", List.of(c));

        String json = objectMapper.writeValueAsString(turn);
        ChatTurn back = objectMapper.readValue(json, ChatTurn.class);

        assertThat(back.candidates()).hasSize(1);
        assertThat(back.candidates().get(0).name()).isEqualTo("성산일출봉");
    }

    @Test
    @DisplayName("candidates 없는 옛 JSON은 빈 배열로 역직렬화된다")
    void 옛_JSON은_빈_배열이_된다() throws Exception {
        ChatTurn back = objectMapper.readValue(
                "{\"role\":\"user\",\"content\":\"안녕\"}", ChatTurn.class);
        assertThat(back.candidates()).isEmpty();
    }
}
