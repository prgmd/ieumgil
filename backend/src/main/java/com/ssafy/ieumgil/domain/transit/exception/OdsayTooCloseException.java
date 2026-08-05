package com.ssafy.ieumgil.domain.transit.exception;

/**
 * ODsay가 "출·도착지가 700m 이내"({@code code -98})라고 답했다 — 경로가 없는 것이 아니라
 * <b>걸어갈 거리라 대중교통 경로를 낼 필요가 없다</b>는 뜻이다.
 *
 * <p>{@link OdsayNoRouteException}과 나누는 이유가 정반대의 의미이기 때문이다. 경로 없음은
 * "그 수단으로는 갈 수 없다"이지만 이쪽은 "이미 다 왔다"다. 실제로 이 둘을 뭉갰다가
 * <b>서울→속초에서 고속버스 후보가 통째로 사라졌다</b> — 하차 지점(속초시외버스터미널)이
 * 목적지(속초시청)에서 700m 이내라 이탈 경로 조회가 {@code -98}로 떨어지고, 그것이
 * 조회 실패로 취급되어 그 수단 후보를 만들지 않았다. 목적지가 터미널 근처인 것은
 * 최선의 경우인데 그 때문에 수단이 지워졌다.
 *
 * <p>에러 코드는 {@code ROUTE_NOT_FOUND}(404)다 — 단건 경로 조회 호출자에게는 "이 구간에
 * 낼 대중교통 경로가 없다"가 여전히 맞는 답이다. 접근·이탈 조회는 이 예외를 잡아
 * 도보 구간으로 대신 채운다.
 */
public class OdsayTooCloseException extends TransitException {

    public OdsayTooCloseException() {
        super(TransitErrorCode.ROUTE_NOT_FOUND);
    }
}
