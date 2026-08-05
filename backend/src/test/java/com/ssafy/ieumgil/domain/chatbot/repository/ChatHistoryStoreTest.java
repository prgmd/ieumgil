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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Redis 자체가 죽었을 때의 강등 — 실제 Redis로는 재현할 수 없어 목으로 본다.
 * 키 설계·TTL·10턴 창·자가 치유 같은 실동작은 {@link ChatHistoryStoreIntegrationTest}에서 본다.
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
    @DisplayName("중간 항목 손상으로 짝이 어긋나도 로드 결과는 user-선두 + 교대를 유지한다")
    void loadHistoryRealignsPairsWhenMiddleEntryCorrupted() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        // 저장 순서 U0,A0,U1,A1,U2,A2 에서 U1이 깨진 문자열로 남음 → 개별 드롭 시 A0,A1 인접
        List<String> raw = List.of(
                json(objectMapper, ChatTurn.ROLE_USER, "u0"),
                json(objectMapper, ChatTurn.ROLE_ASSISTANT, "a0"),
                "{ 이건 JSON이 아니다",
                json(objectMapper, ChatTurn.ROLE_ASSISTANT, "a1"),
                json(objectMapper, ChatTurn.ROLE_USER, "u2"),
                json(objectMapper, ChatTurn.ROLE_ASSISTANT, "a2")
        );
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(any(), anyLong(), anyLong())).thenReturn(raw);
        ChatHistoryStore store = new ChatHistoryStore(redisTemplate, objectMapper);

        List<ChatTurn> history = store.loadHistory(1L, 1L);

        assertThat(history).containsExactly(
                new ChatTurn(ChatTurn.ROLE_USER, "u0"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "a0"),
                new ChatTurn(ChatTurn.ROLE_USER, "u2"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "a2")
        );
        assertAlternatingUserFirst(history);
    }

    @Test
    @DisplayName("선두 직후 항목 손상에도 선두 assistant를 버려 첫 메시지 user를 보장한다")
    void loadHistoryDropsLeadingAssistantWhenPairBrokenAfterHead() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        // 저장 순서 U0,A0,U1,A1 에서 A0가 깨짐 → 개별 드롭 시 U0,U1,A1 → U0는 짝 없어 버려짐
        List<String> raw = List.of(
                json(objectMapper, ChatTurn.ROLE_USER, "u0"),
                "{ 이건 JSON이 아니다",
                json(objectMapper, ChatTurn.ROLE_USER, "u1"),
                json(objectMapper, ChatTurn.ROLE_ASSISTANT, "a1")
        );
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(any(), anyLong(), anyLong())).thenReturn(raw);
        ChatHistoryStore store = new ChatHistoryStore(redisTemplate, objectMapper);

        List<ChatTurn> history = store.loadHistory(1L, 1L);

        assertThat(history).containsExactly(
                new ChatTurn(ChatTurn.ROLE_USER, "u1"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "a1")
        );
        assertAlternatingUserFirst(history);
    }

    @Test
    @DisplayName("연속 두 항목 손상 시 원본에서 떨어진 문답을 접착하지 않는다 — 원본 인덱스 인접만 짝으로 인정")
    void loadHistoryDoesNotGluePairsAcrossTwoAdjacentCorruptedEntries() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        // 저장 순서 U0,A0,U1,A1,U2,A2 에서 A0·U1이 연속으로 깨짐.
        // 개별 드롭 후 재페어링하면 U0-A1이 붙어 보이지만, A1은 U1의 답이지 U0의 답이 아니다.
        // 원본 인덱스 인접(둘 다 non-null)만 짝으로 인정하므로 U0는 고아로 버려지고 U2-A2만 남아야 한다.
        List<String> raw = List.of(
                json(objectMapper, ChatTurn.ROLE_USER, "u0"),
                "{ 이건 JSON이 아니다 A0",
                "{ 이건 JSON이 아니다 U1",
                json(objectMapper, ChatTurn.ROLE_ASSISTANT, "a1"),
                json(objectMapper, ChatTurn.ROLE_USER, "u2"),
                json(objectMapper, ChatTurn.ROLE_ASSISTANT, "a2")
        );
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(any(), anyLong(), anyLong())).thenReturn(raw);
        ChatHistoryStore store = new ChatHistoryStore(redisTemplate, objectMapper);

        List<ChatTurn> history = store.loadHistory(1L, 1L);

        assertThat(history).containsExactly(
                new ChatTurn(ChatTurn.ROLE_USER, "u2"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "a2")
        );
        assertAlternatingUserFirst(history);
    }

    private String json(ObjectMapper objectMapper, String role, String content) throws Exception {
        return objectMapper.writeValueAsString(new ChatTurn(role, content));
    }

    private void assertAlternatingUserFirst(List<ChatTurn> history) {
        assertThat(history.size() % 2).isZero();
        for (int i = 0; i < history.size(); i++) {
            String expected = i % 2 == 0 ? ChatTurn.ROLE_USER : ChatTurn.ROLE_ASSISTANT;
            assertThat(history.get(i).role()).isEqualTo(expected);
        }
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
