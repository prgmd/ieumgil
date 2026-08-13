package com.ssafy.ieumgil.domain.transit.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelInvokerTest {

    @Test
    @DisplayName("모든 task가 성공하면 입력 순서대로 결과를 돌려준다")
    void 모든_task_성공() throws InterruptedException {
        List<Callable<String>> tasks = List.of(() -> "a", () -> "b", () -> "c");

        List<String> result =
                ParallelInvoker.invokeAllWithin(tasks, Duration.ofSeconds(5), i -> "fallback" + i, "test");

        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("task가 예외로 끝나면 그 index만 폴백으로 대체하고 나머지 결과는 유지한다")
    void 실패한_task만_폴백() throws InterruptedException {
        List<Callable<String>> tasks = List.of(
                () -> "ok0",
                () -> {
                    throw new IllegalStateException("boom");
                },
                () -> "ok2");

        List<String> result =
                ParallelInvoker.invokeAllWithin(tasks, Duration.ofSeconds(5), i -> "fallback" + i, "test");

        assertThat(result).containsExactly("ok0", "fallback1", "ok2");
    }

    @Test
    @DisplayName("상한을 넘긴 task는 타임아웃 취소로 폴백되고, 제시간에 끝난 task는 그대로 남는다")
    void 타임아웃_task만_폴백() throws InterruptedException {
        List<Callable<String>> tasks = List.of(
                () -> "fast",
                () -> {
                    Thread.sleep(2_000);
                    return "slow";
                });

        List<String> result =
                ParallelInvoker.invokeAllWithin(tasks, Duration.ofMillis(200), i -> "timeout" + i, "test");

        assertThat(result).containsExactly("fast", "timeout1");
    }

    @Test
    @DisplayName("invokeAll 대기 중 인터럽트되면 InterruptedException을 그대로 던진다 — 호출자가 대응한다")
    void 인터럽트되면_예외를_던진다() {
        List<Callable<String>> tasks = List.of(() -> {
            Thread.sleep(5_000);
            return "never";
        });
        // 호출 스레드의 인터럽트 플래그를 미리 세워 invokeAll의 타임드 대기가 즉시 인터럽트되게 한다.
        Thread.currentThread().interrupt();

        assertThatThrownBy(() ->
                ParallelInvoker.invokeAllWithin(tasks, Duration.ofSeconds(10), i -> "fallback", "test"))
                .isInstanceOf(InterruptedException.class);

        // InterruptedException이 던져지며 플래그는 이미 해제됐다 — 다음 테스트로 새지 않게 확인 겸 정리한다.
        assertThat(Thread.interrupted()).isFalse();
    }
}
