package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.exception.OdsayNoRouteException;
import com.ssafy.ieumgil.domain.transit.exception.OdsayTooCloseException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * ODsay 복합 경로 조회 한 번을 세 가지 결과로 분류해 들고 온다.
 *
 * <p>1단({@code TransitCandidateServiceImpl}의 구간별 경로 조회)과 2단
 * ({@code IntercityCandidateAssembler}의 접근·이탈 조회)이 같은 조회를 쓰므로 공통 협력자로 뺐다.
 * 어느 쪽이든 예외를 밖으로 던지지 않는다 — 한 수단이 죽었다고 구간 전체를 못 내면 안 된다.
 *
 * <p>{@code TransitCandidateServiceImpl}이 주입받은 협력자로 직접 조립한다(Spring 빈이 아니다) —
 * 그 서비스의 단위 테스트가 {@code @InjectMocks}로 목을 꽂기 때문에 서비스 생성자 시그니처를
 * 그대로 유지해야 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class TransitRouteLookup {

    private final PublicTransitQueryService publicTransitQueryService;

    /**
     * 대중교통 복합 경로 조회. "조회에 실패해서 비었다"와 "ODsay가 경로가 없다고 답했다",
     * "700m 이내라 낼 경로가 없다"를 구분해서 들고 온다 — 세 경우에 사용자가 할 행동이 다르다.
     */
    public RouteLookup of(double startLat, double startLng, double endLat, double endLng) {
        try {
            List<OdsayRouteResponse.Path> paths =
                    publicTransitQueryService.getCombinedRoutes(startLat, startLng, endLat, endLng);
            return new RouteLookup(paths, false, false);
        } catch (OdsayNoRouteException e) {
            log.info("ODsay가 경로를 주지 않는 구간: {},{} -> {},{}", startLat, startLng, endLat, endLng);
            return new RouteLookup(List.of(), true, false);
        } catch (OdsayTooCloseException e) {
            log.info("700m 이내라 대중교통 경로가 없는 구간 — 걸어갈 거리다: {},{} -> {},{}",
                    startLat, startLng, endLat, endLng);
            return new RouteLookup(List.of(), false, true);
        } catch (RuntimeException e) {
            log.warn("대중교통 경로 목록 조회 실패: {},{} -> {},{}", startLat, startLng, endLat, endLng, e);
            return new RouteLookup(List.of(), false, false);
        }
    }

    /** 경로 조회를 아예 하지 않은 구간(도보만)의 빈 결과. */
    public static RouteLookup empty() {
        return new RouteLookup(List.of(), false, false);
    }

    /**
     * 경로 조회 결과. 빈 목록 하나로는 세 가지가 구분되지 않는다 — 조회 실패,
     * ODsay가 "경로 없음"이라고 답함({@code noRoute}), 700m 이내라 낼 경로가 없음
     * ({@code tooClose}). 뒤 둘은 영구적인 답이지만 사용자가 할 일이 정반대다:
     * 경로 없음은 "그 수단으로는 못 간다", 700m 이내는 "걸어가면 된다"다.
     */
    public record RouteLookup(List<OdsayRouteResponse.Path> paths, boolean noRoute, boolean tooClose) {
    }
}
