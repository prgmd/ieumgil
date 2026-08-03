package com.ssafy.ieumgil.domain.chatbot.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Redis 자체가 죽었을 때의 강등 — 실제 Redis로는 재현할 수 없어 목으로 본다.
 * 키 설계·TTL·6턴 창·자가 치유 같은 실동작은 {@link ChatHistoryStoreIntegrationTest}에서 본다.
 */
@ExtendWith(MockitoExtension.class)
class ChatHistoryStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;

    @Test
    @DisplayName("Redis가 죽어도 이력 조회는 빈 이력으로 강등된다 — 이력은 부가 기능인데 필수 경로에 있다")
    void loadHistoryDegradesToEmptyWhenRedisIsDown() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(any(), any(Long.class), any(Long.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        ChatHistoryStore store = new ChatHistoryStore(redisTemplate, new ObjectMapper());

        List<ChatTurn> history = store.loadHistory(1L, 1L);

        assertThat(history).isEmpty();
    }

    @Test
    @DisplayName("Redis가 죽어도 이력 저장 실패로 응답이 깨지지 않는다 — 답변은 이미 만들어졌다")
    void appendExchangeDegradesWhenRedisIsDown() {
        when(redisTemplate.execute(any(SessionCallback.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        ChatHistoryStore store = new ChatHistoryStore(redisTemplate, new ObjectMapper());

        assertThatCode(() -> store.appendExchange(
                1L, 1L,
                new ChatTurn(ChatTurn.ROLE_USER, "안녕"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "안녕하세요")
        )).doesNotThrowAnyException();
    }
}
