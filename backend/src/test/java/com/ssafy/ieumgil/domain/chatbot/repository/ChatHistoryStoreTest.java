package com.ssafy.ieumgil.domain.chatbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatHistoryStoreTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private ChatHistoryStore chatHistoryStore;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        chatHistoryStore = new ChatHistoryStore(redisTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void appendAndLoadRoundTrip() {
        Long projectId = 9001L;
        Long memberId = 9001L;
        redisTemplate.delete("chatbot:history:9001:9001");

        chatHistoryStore.appendExchange(
                projectId, memberId,
                new ChatTurn(ChatTurn.ROLE_USER, "안녕"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "안녕하세요")
        );

        List<ChatTurn> history = chatHistoryStore.loadHistory(projectId, memberId);

        assertThat(history).containsExactly(
                new ChatTurn(ChatTurn.ROLE_USER, "안녕"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "안녕하세요")
        );
    }

    /** 윈도우는 토큰 절감을 위해 6턴으로 줄였다(커밋 8875e7f). 이 숫자가 바뀌면 여기서 깨진다. */
    @Test
    void keepsOnlyMostRecentSixTurns() {
        Long projectId = 9002L;
        Long memberId = 9002L;
        redisTemplate.delete("chatbot:history:9002:9002");

        for (int i = 0; i < 15; i++) {
            chatHistoryStore.appendExchange(
                    projectId, memberId,
                    new ChatTurn(ChatTurn.ROLE_USER, "user-" + i),
                    new ChatTurn(ChatTurn.ROLE_ASSISTANT, "assistant-" + i)
            );
        }

        List<ChatTurn> history = chatHistoryStore.loadHistory(projectId, memberId);

        assertThat(history).hasSize(12);
        assertThat(history.get(0)).isEqualTo(new ChatTurn(ChatTurn.ROLE_USER, "user-9"));
        assertThat(history.get(11)).isEqualTo(new ChatTurn(ChatTurn.ROLE_ASSISTANT, "assistant-14"));
    }
}
