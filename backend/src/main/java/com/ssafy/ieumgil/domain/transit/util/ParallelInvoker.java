package com.ssafy.ieumgil.domain.transit.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * 가상 스레드로 여러 외부 호출을 동시에 실행하고 상한을 강제하는 헬퍼.
 *
 * <p>{@code TransitCandidateServiceImpl}의 1단(구간별 경로 조회)과
 * {@code IntercityCandidateAssembler}의 2단(수단별 시간표 조회)이 똑같은 스캐폴딩을 쓴다 —
 * {@link Executors#newVirtualThreadPerTaskExecutor} + {@link ExecutorService#invokeAll}로 상한을 걸고,
 * 각 {@link Future}를 인터럽트·타임아웃 취소·실행 실패의 세 갈래로 받아 실패한 것만 폴백으로 내린다.
 * 하나가 늦거나 죽어도 나머지를 잃지 않는다.
 *
 * <p>{@code invokeAll} 자체가 인터럽트되면 {@link InterruptedException}을 그대로 던진다 —
 * 호출자마다 그 상황에서 할 일이 다르기 때문이다(요청 전체 거절 vs 폴백 목록 반환).
 */
@Slf4j
public final class ParallelInvoker {

    private ParallelInvoker() {
    }

    /**
     * {@code tasks}를 가상 스레드로 동시에 실행하고 {@code timeout} 안에 끝난 결과를 입력 순서대로 돌려준다.
     * 인터럽트·타임아웃 취소·실행 실패로 끝난 항목은 {@code fallback.apply(index)}로 대체한다.
     *
     * @param label 로그에 남길 조회 이름(예: "교통 후보 구간 조회")
     * @throws InterruptedException {@code invokeAll} 대기 중 인터럽트된 경우 — 호출자가 대응을 결정한다
     */
    public static <T> List<T> invokeAllWithin(
            List<? extends Callable<T>> tasks, Duration timeout, IntFunction<T> fallback, String label)
            throws InterruptedException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<T>> futures = executor.invokeAll(tasks, timeout.toMillis(), TimeUnit.MILLISECONDS);
            List<T> results = new ArrayList<>(futures.size());
            for (int i = 0; i < futures.size(); i++) {
                results.add(resolve(futures.get(i), i, fallback, label));
            }
            return results;
        } finally {
            // invokeAll이 이미 취소했지만, 인터럽트에 늦게 반응하는 호출을 기다리지 않기 위해 즉시 내린다.
            executor.shutdownNow();
        }
    }

    /** 타임아웃·예외로 끝난 항목만 폴백으로 내려간다 — 나머지 결과까지 잃지 않는다. */
    private static <T> T resolve(Future<T> future, int index, IntFunction<T> fallback, String label) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} 인터럽트: index={}", label, index);
            return fallback.apply(index);
        } catch (CancellationException e) {
            log.warn("{} 타임아웃 취소: index={}", label, index);
            return fallback.apply(index);
        } catch (ExecutionException e) {
            log.warn("{} 실패: index={}", label, index, e.getCause());
            return fallback.apply(index);
        }
    }
}
