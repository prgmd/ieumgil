package com.ssafy.ieumgil.domain.chatbot.repository;

import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대화 이력 저장소 — Testcontainers Redis에 붙는다.
 *
 * 예전에는 {@code new LettuceConnectionFactory("localhost", 6379)}로 개발자 로컬의 실제 Redis를
 * 잡았다. CI·팀원 PC에서는 접속 실패로 red였고, 로컬에서는 개발용 데이터가 있는 Redis에 키를
 * 쓰고 지웠다. 다른 통합 테스트와 같은 싱글턴 컨테이너를 쓰도록 옮겼다.
 */
class ChatHistoryStoreIntegrationTest extends IntegrationTestSupport {

    private static final String KEY_FORMAT = "chatbot:history:%d:%d";   // ChatHistoryStore와 동일

    @Autowired
    ChatHistoryStore chatHistoryStore;
    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearKeys() {
        // 컨테이너는 JVM당 하나라 다른 테스트가 남긴 키와 섞일 수 있다
        redisTemplate.delete(List.of(
                KEY_FORMAT.formatted(9001, 9001),
                KEY_FORMAT.formatted(9002, 9002),
                KEY_FORMAT.formatted(9003, 9003),
                KEY_FORMAT.formatted(9004, 9004)
        ));
    }

    @Test
    void appendAndLoadRoundTrip() {
        Long projectId = 9001L;
        Long memberId = 9001L;

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

    @Test
    @DisplayName("키 이름과 TTL이 규약대로 붙는다 — 만료가 빠지면 이력이 영구히 남는다")
    void writesToNamespacedKeyWithTtl() {
        chatHistoryStore.appendExchange(
                9001L, 9001L,
                new ChatTurn(ChatTurn.ROLE_USER, "안녕"),
                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "안녕하세요")
        );

        Long ttlSeconds = redisTemplate.getExpire(KEY_FORMAT.formatted(9001, 9001));

        assertThat(ttlSeconds).isBetween(1L, TimeUnit.DAYS.toSeconds(1));
    }

    @Test
    @DisplayName("깨진 항목 하나가 나머지 이력을 막지 않는다 — TTL 1일 동안 500이 되던 자리")
    void skipsCorruptedEntryInsteadOfFailingWholeHistory() {
        Long projectId = 9003L;
        Long memberId = 9003L;
        String key = KEY_FORMAT.formatted(projectId, memberId);

        redisTemplate.opsForList().rightPush(key, "{ 이건 JSON이 아니다");
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

    @Test
    @DisplayName("동시 전송에도 문답 짝이 끼어들지 않는다 — 두 탭·더블클릭이 만들던 어긋남")
    void concurrentAppendsKeepUserAssistantPairsContiguous() throws InterruptedException {
        Long projectId = 9004L;
        Long memberId = 9004L;
        int threads = 8;
        int perThread = 4;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            int threadNo = t;
            executor.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        chatHistoryStore.appendExchange(
                                projectId, memberId,
                                new ChatTurn(ChatTurn.ROLE_USER, "user-%d-%d".formatted(threadNo, i)),
                                new ChatTurn(ChatTurn.ROLE_ASSISTANT, "assistant-%d-%d".formatted(threadNo, i))
                        );
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        List<ChatTurn> history = chatHistoryStore.loadHistory(projectId, memberId);

        // 짝이 원자적으로 들어갔다면 잘린 창은 언제나 user로 시작해 user/assistant가 번갈아 나온다
        assertThat(history).hasSize(12);
        for (int i = 0; i < history.size(); i++) {
            String expectedRole = i % 2 == 0 ? ChatTurn.ROLE_USER : ChatTurn.ROLE_ASSISTANT;
            assertThat(history.get(i).role()).isEqualTo(expectedRole);
        }
    }
}
