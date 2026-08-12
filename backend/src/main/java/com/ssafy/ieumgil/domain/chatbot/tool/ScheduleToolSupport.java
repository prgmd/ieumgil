package com.ssafy.ieumgil.domain.chatbot.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 시외 교통 스케줄 tool(버스·기차·항공)의 공통 조회 골격.
 *
 * <p>세 tool은 provider에서 스케줄을 받아 출발 시각 오름차순으로 정렬해 최대 10건만 남기고,
 * provider가 실패하면 빈 목록으로 떨어진다는 흐름이 완전히 같다. DTO·provider 타입과 로그
 * 구분자만 다르므로 그 골격을 여기 모은다. 반환 타입·건수 상한·정렬 기준은 그대로 보존한다.
 */
@Slf4j
final class ScheduleToolSupport {

    private static final int MAX_RESULTS = 10;

    private ScheduleToolSupport() {
    }

    static <T> List<T> topByDepartureTime(String departureName, String arrivalName,
                                          Supplier<List<T>> fetch,
                                          Function<T, String> departureTime,
                                          String label) {
        try {
            return fetch.get().stream()
                    .sorted(Comparator.comparing(departureTime))
                    .limit(MAX_RESULTS)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("{} schedule tool call failed: {} -> {}", label, departureName, arrivalName, e);
            return List.of();
        }
    }
}
