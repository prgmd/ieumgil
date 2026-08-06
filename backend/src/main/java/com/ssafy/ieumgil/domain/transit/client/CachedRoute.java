package com.ssafy.ieumgil.domain.transit.client;

import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.exception.OdsayNoRouteException;
import com.ssafy.ieumgil.domain.transit.exception.OdsayTooCloseException;

import java.util.List;

/**
 * 캐시에 담는 경로 조회 결과. 경로 목록 하나로는 부족하다 — 이 조회는 세 갈래로 끝나고
 * 세 갈래가 사용자에게 서로 다른 뜻이다.
 *
 * <ul>
 *   <li>{@code OK} — 경로가 있다(빈 목록도 유효한 OK다)
 *   <li>{@code NO_ROUTE} — ODsay가 "경로 없음"이라고 답했다(도서 등). 다시 물어도 같다
 *   <li>{@code TOO_CLOSE} — 700m 이내라 낼 경로가 없다. 걸어가면 된다
 * </ul>
 *
 * <p>뒤 둘도 캐시한다(negative caching). 캐시하지 않으면 울릉도를 담은 일정이 매번 ODsay를
 * 부르는데, 그 답은 영구적이라 부를 이유가 없다. 다만 TTL은 짧게 둔다 — ODsay가 노선을
 * 새로 넣으면 답이 바뀔 수 있고, 우리가 그걸 하루 내내 모르고 있으면 안 된다.
 *
 * <p><b>진짜 장애({@code TransitException})는 캐시하지 않는다.</b> 캐시하면 일시적인 429나
 * 타임아웃이 TTL 내내 굳어 재시도가 무의미해진다 — 재시도가 유효한 유일한 상태를 재시도
 * 불가로 바꾸는 셈이다.
 */
record CachedRoute(Outcome outcome, List<OdsayRouteResponse.Path> paths) {

    enum Outcome {
        OK, NO_ROUTE, TOO_CLOSE
    }

    static CachedRoute ok(List<OdsayRouteResponse.Path> paths) {
        return new CachedRoute(Outcome.OK, paths);
    }

    static CachedRoute noRoute() {
        return new CachedRoute(Outcome.NO_ROUTE, List.of());
    }

    static CachedRoute tooClose() {
        return new CachedRoute(Outcome.TOO_CLOSE, List.of());
    }

    /** 캐시된 갈래를 원래 호출이 했던 것과 똑같이 재현한다 — 히트와 미스가 구분되지 않아야 한다 */
    List<OdsayRouteResponse.Path> pathsOrThrow() {
        return switch (outcome) {
            case OK -> paths == null ? List.of() : paths;
            case NO_ROUTE -> throw new OdsayNoRouteException();
            case TOO_CLOSE -> throw new OdsayTooCloseException();
        };
    }
}
