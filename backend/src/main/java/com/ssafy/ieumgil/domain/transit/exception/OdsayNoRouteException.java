package com.ssafy.ieumgil.domain.transit.exception;

/**
 * ODsay가 "그 구간에는 경로가 없다"고 답했다 — 장애가 아니라 <b>영구적인 답</b>이다.
 *
 * <p>{@link TransitException}과 클래스를 나누는 이유는 사용자가 할 행동이 다르기 때문이다.
 * 조회 실패는 재시도가 답이지만 경로 없음은 몇 번을 다시 물어도 같은 답이 온다 — 실측 157경로
 * 중 38개(울릉도·독도·백령도·흑산도 등 도서 전량)가 여기다. 하나로 뭉치면 후보가
 * {@code LOOKUP_FAILED}로 나가 화면에 "조회 실패, 재시도"가 뜬다.
 *
 * <p>에러 코드는 {@code ROUTE_NOT_FOUND}(404)다 — 다른 호출자(단건 경로 조회)에게도
 * "그 구간에는 경로가 없다"가 맞는 답이다. 다만 <b>같은 코드를 쓰는 평범한
 * {@code TransitException}과는 구분된다</b> — 경로 목록이 그냥 비어 있는 경우
 * ({@code PublicTransitQueryServiceImpl})는 ODsay가 경로 없음을 명시한 것이 아니므로
 * 이 예외가 아니다.
 */
public class OdsayNoRouteException extends TransitException {

    public OdsayNoRouteException() {
        super(TransitErrorCode.ROUTE_NOT_FOUND);
    }
}
