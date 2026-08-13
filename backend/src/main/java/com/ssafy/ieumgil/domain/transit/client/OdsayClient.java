package com.ssafy.ieumgil.domain.transit.client;

import com.ssafy.ieumgil.domain.transit.dto.OdsayBusScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayBusTerminalResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayFlightScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayRouteResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainTerminalResponse;
import com.ssafy.ieumgil.domain.transit.exception.OdsayNoRouteException;
import com.ssafy.ieumgil.domain.transit.exception.OdsayTooCloseException;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Slf4j
@Component
public class OdsayClient {

    /**
     * ODsay가 "그 구간에는 경로가 없다"로 답하는 코드 — {@code -99} 검색결과 없음(울릉도),
     * {@code 3} 출발지 정류장 없음(백령도). 실측 157경로 중 38개(도서 전량)가 이 둘이다.
     */
    private static final Set<String> NO_ROUTE_CODES = Set.of("-99", "3");
    /**
     * ODsay가 "출·도착지가 700m 이내"라고 답하는 코드. 경로가 없는 것이 아니라 걸어갈 거리라
     * 낼 경로가 없다는 뜻이므로 {@link #NO_ROUTE_CODES}와 섞지 않는다
     * ({@link OdsayTooCloseException}).
     */
    private static final String TOO_CLOSE_CODE = "-98";

    /**
     * 경로 캐시 TTL. 시간표만큼 길게 두지 않는 이유는 경로 목록에 배차 간격·소요가 섞여 있고
     * 그 값이 시간대에 따라 달라지기 때문이다.
     */
    private static final Duration ROUTE_TTL = Duration.ofHours(1);
    /** 시간표 TTL. 하루 단위로 거의 바뀌지 않지만, 임시 감편을 반나절 넘게 모르고 있으면 안 된다 */
    private static final Duration SCHEDULE_TTL = Duration.ofHours(6);
    /**
     * "경로 없음"·"700m 이내" TTL. 영구적인 답이지만 짧게 둔다 — ODsay가 노선을 새로 넣으면
     * 답이 바뀌고, 그걸 하루 내내 모르고 있을 이유가 없다.
     */
    private static final Duration NEGATIVE_TTL = Duration.ofMinutes(10);

    /**
     * 레이트 리밋(429) 백오프. 캐시가 1차 방어지만, 시외 한 구간이 경로 조회 7회를 쓰므로
     * 캐시 미스가 몰리면 429가 난다({@link #searchPublicTransitRoute} 주석). 최대 3회 시도,
     * 지수 증가 대기(500ms → 1000ms). <b>429만</b> 재시도하고 다른 상태코드는 즉시 던진다.
     */
    private static final int MAX_429_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 500L;

    private final RestClient restClient;
    private final OdsayProperties properties;
    private final TransitApiCache cache;

    public OdsayClient(RestClient.Builder builder, OdsayProperties properties, TransitApiCache cache) {
        this.properties = properties;
        this.cache = cache;
        this.restClient = builder.build();
    }

    /**
     * 대중교통 경로 조회. 캐시를 먼저 본다 — 같은 구간을 다시 물으면 ODsay를 부르지 않는다.
     *
     * <p>이 메서드가 캐시 지점인 이유: 1단 구간 조회와 접근·이탈 조회가 모두 여기로 모인다.
     * 특히 <b>접근·이탈은 역·터미널 좌표가 고정값</b>이라 같은 역을 쓰는 모든 구간이 한 엔트리를
     * 공유한다 — 히트율이 가장 높은 자리다. 시외 구간 하나가 경로 조회 7회(1단 1 + 수단 3종 ×
     * 접근·이탈 2)를 쓰고 그 호출들이 요청 내 중복 제거를 우회하므로, 캐시가 없으면 여러 구간을
     * 담은 요청이 ODsay 레이트 리밋(429)에 걸린다(실측).
     *
     * <p>"경로 없음"·"700m 이내"도 캐시하되 진짜 장애는 캐시하지 않는다({@link CachedRoute}).
     */
    public List<OdsayRouteResponse.Path> searchPublicTransitRoute(
            double startLat, double startLng, double endLat, double endLng, String mode) {
        String cacheKey = TransitCacheKeys.route(startLat, startLng, endLat, endLng, mode);
        Optional<CachedRoute> cached = cache.read(cacheKey, new TypeReference<CachedRoute>() {
        });
        if (cached.isPresent()) {
            return cached.get().pathsOrThrow();
        }
        try {
            int searchPathType = "BUS".equals(mode) ? 2 : "SUBWAY".equals(mode) ? 1 : 0;
            URI uri = URI.create(properties.baseUrl() + "/searchPubTransPathT"
                    + "?SX=" + startLng + "&SY=" + startLat
                    + "&EX=" + endLng + "&EY=" + endLat
                    + "&apiKey=" + properties.apiKey()
                    + "&SearchPathType=" + searchPathType);
            OdsayRouteResponse response = executeWith429Retry(() -> restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayRouteResponse.class));
            checkRouteError(response);
            List<OdsayRouteResponse.Path> paths =
                    response == null || response.result() == null || response.result().path() == null
                            ? List.of()
                            : response.result().path();
            cache.write(cacheKey, CachedRoute.ok(paths), ROUTE_TTL);
            return paths;
        } catch (OdsayNoRouteException e) {
            cache.write(cacheKey, CachedRoute.noRoute(), NEGATIVE_TTL);
            throw e;
        } catch (OdsayTooCloseException e) {
            cache.write(cacheKey, CachedRoute.tooClose(), NEGATIVE_TTL);
            throw e;
        } catch (RestClientException | IllegalArgumentException e) {
            // URL에 apiKey가 쿼리로 붙으므로 e.getMessage()(요청 URL 포함)를 로그에 남기지 않는다 — 예외 타입만 남긴다.
            log.warn("ODsay 대중교통 길찾기 실패: {}", e.getClass().getSimpleName());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }

    public Optional<OdsayTrainTerminalResponse.Terminal> searchTrainTerminal(String terminalName) {
        try {
            URI uri = URI.create(properties.baseUrl() + "/trainTerminals"
                    + "?terminalName=" + URLEncoder.encode(terminalName, StandardCharsets.UTF_8)
                    + "&apiKey=" + properties.apiKey());
            OdsayTrainTerminalResponse response = executeWith429Retry(() -> restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayTrainTerminalResponse.class));
            checkForError(response == null ? null : response.error());
            if (response == null || response.result() == null || response.result().isEmpty()) {
                return Optional.empty();
            }
            return selectBestMatch(response.result(), terminalName,
                    OdsayTrainTerminalResponse.Terminal::stationName,
                    OdsayTrainTerminalResponse.Terminal::haveDestinationTerminals);
        } catch (RestClientException | IllegalArgumentException e) {
            // URL에 apiKey가 붙으므로 예외 메시지(URL 포함) 대신 타입만 남긴다.
            log.warn("ODsay 기차역 검색 실패: {}", e.getClass().getSimpleName());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }

    public Optional<OdsayBusTerminalResponse.Terminal> searchExpressBusTerminal(String terminalName) {
        return searchBusTerminal("expressBusTerminals", terminalName);
    }

    public Optional<OdsayBusTerminalResponse.Terminal> searchIntercityBusTerminal(String terminalName) {
        return searchBusTerminal("intercityBusTerminals", terminalName);
    }

    private Optional<OdsayBusTerminalResponse.Terminal> searchBusTerminal(String path, String terminalName) {
        try {
            URI uri = URI.create(properties.baseUrl() + "/" + path
                    + "?terminalName=" + URLEncoder.encode(terminalName, StandardCharsets.UTF_8)
                    + "&apiKey=" + properties.apiKey());
            OdsayBusTerminalResponse response = executeWith429Retry(() -> restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayBusTerminalResponse.class));
            checkForError(response == null ? null : response.error());
            if (response == null || response.result() == null || response.result().isEmpty()) {
                return Optional.empty();
            }
            return selectBestMatch(response.result(), terminalName,
                    OdsayBusTerminalResponse.Terminal::stationName,
                    OdsayBusTerminalResponse.Terminal::haveDestinationTerminals);
        } catch (RestClientException | IllegalArgumentException e) {
            // URL에 apiKey가 붙으므로 예외 메시지(URL 포함) 대신 타입만 남긴다.
            log.warn("ODsay 버스터미널 검색 실패: {}", e.getClass().getSimpleName());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }

    /**
     * ODsay 터미널검색은 부분일치라 같은 검색어에 여러 역이 걸릴 수 있다(예: "경주" 검색 시
     * "서경주"가 먼저 옴). 검색어와 이름이 정확히 같고 목적지가 있는 항목을 우선하고,
     * 없으면 목적지가 있는 첫 항목으로 폴백한다.
     */
    private <T> Optional<T> selectBestMatch(
            List<T> candidates, String query, Function<T, String> nameOf, Predicate<T> hasDestinations) {
        return candidates.stream()
                .filter(c -> query.equals(nameOf.apply(c)) && hasDestinations.test(c))
                .findFirst()
                .or(() -> candidates.stream().filter(hasDestinations).findFirst());
    }

    public List<OdsayTrainScheduleResponse.Train> getTrainSchedule(int startStationId, int endStationId) {
        return fetchSchedule("train", "/trainServiceTime", startStationId, endStationId,
                OdsayTrainScheduleResponse.class,
                new TypeReference<List<OdsayTrainScheduleResponse.Train>>() {
                },
                OdsayTrainScheduleResponse::error,
                r -> r.result() == null || r.result().station() == null ? List.of() : r.result().station(),
                "ODsay 기차 시간표 조회 실패");
    }

    public List<OdsayBusScheduleResponse.Bus> getIntercityBusSchedule(int startStationId, int endStationId) {
        return fetchSchedule("bus", "/searchInterBusSchedule", startStationId, endStationId,
                OdsayBusScheduleResponse.class,
                new TypeReference<List<OdsayBusScheduleResponse.Bus>>() {
                },
                OdsayBusScheduleResponse::error,
                r -> r.result() == null || r.result().schedule() == null ? List.of() : r.result().schedule(),
                "ODsay 고속/시외버스 시간표 조회 실패");
    }

    public List<OdsayFlightScheduleResponse.Flight> getFlightSchedule(int startStationId, int endStationId) {
        return fetchSchedule("air", "/airServiceTime", startStationId, endStationId,
                OdsayFlightScheduleResponse.class,
                new TypeReference<List<OdsayFlightScheduleResponse.Flight>>() {
                },
                OdsayFlightScheduleResponse::error,
                r -> r.result() == null || r.result().station() == null ? List.of() : r.result().station(),
                "ODsay 국내선 항공 시간표 조회 실패");
    }

    /**
     * 세 시간표 조회(기차·시외버스·항공)의 공통 뼈대 — 캐시 히트 반환, ODsay 호출,
     * {@link #checkForError} 판정, 스케줄 추출, 캐시 저장, try/catch를 한곳에 모은다.
     * 엔드포인트·응답 타입·에러/스케줄 추출자만 수단별로 갈린다.
     */
    private <RESP, ITEM> List<ITEM> fetchSchedule(
            String cacheLabel, String endpoint, int startStationId, int endStationId,
            Class<RESP> responseType, TypeReference<List<ITEM>> cacheType,
            Function<RESP, List<OdsayRouteResponse.OdsayError>> errorOf,
            Function<RESP, List<ITEM>> scheduleOf, String failMessage) {
        String cacheKey = TransitCacheKeys.schedule(cacheLabel, startStationId, endStationId);
        Optional<List<ITEM>> cached = cache.read(cacheKey, cacheType);
        if (cached.isPresent()) {
            return cached.get();
        }
        try {
            URI uri = URI.create(properties.baseUrl() + endpoint
                    + "?startStationID=" + startStationId
                    + "&endStationID=" + endStationId
                    + "&apiKey=" + properties.apiKey());
            RESP response = executeWith429Retry(() -> restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(responseType));
            checkForError(response == null ? null : errorOf.apply(response));
            List<ITEM> schedule = response == null ? List.of() : scheduleOf.apply(response);
            cache.write(cacheKey, schedule, SCHEDULE_TTL);
            return schedule;
        } catch (RestClientException | IllegalArgumentException e) {
            // URL에 apiKey가 붙으므로 예외 메시지(URL 포함) 대신 타입만 남긴다.
            log.warn("{}: {}", failMessage, e.getClass().getSimpleName());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }

    /**
     * ODsay 호출을 429(Too Many Requests)에 한해 지수 백오프로 재시도한다. 재시도 대상은 429뿐이며
     * 그 외 상태코드·예외는 그대로 전파해 호출부의 기존 처리에 맡긴다. 시도를 소진하면 마지막 429를 던진다.
     */
    private <T> T executeWith429Retry(Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (HttpClientErrorException.TooManyRequests e) {
                attempt++;
                if (attempt >= MAX_429_ATTEMPTS) {
                    throw e;
                }
                log.warn("ODsay 429 — {}회차 재시도 대기", attempt);
                try {
                    Thread.sleep(RETRY_BASE_DELAY_MS * (1L << (attempt - 1)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private void checkForError(List<OdsayRouteResponse.OdsayError> errors) {
        if (errors != null && !errors.isEmpty()) {
            errors.forEach(error ->
                    log.warn("ODsay 응답 에러: code={}, message={}", error.code(), error.message()));
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }

    /**
     * 경로 검색 응답의 에러 판정. 다른 엔드포인트와 달리 <b>코드로 갈라야</b> 한다 —
     * "그 구간에는 경로가 없다"와 "API가 죽었다"는 사용자가 할 행동이 다르다
     * ({@link OdsayNoRouteException} 참고). 에러가 객체형·배열형 두 가지로 오므로
     * 코드는 {@link OdsayRouteResponse#errorCode()}가 뽑는다.
     */
    private void checkRouteError(OdsayRouteResponse response) {
        String code = response == null ? null : response.errorCode();
        if (code == null) {
            return;
        }
        if (NO_ROUTE_CODES.contains(code)) {
            log.info("ODsay 경로 없음: code={}", code);
            throw new OdsayNoRouteException();
        }
        if (TOO_CLOSE_CODE.equals(code)) {
            log.info("ODsay 700m 이내 — 걸어갈 거리라 경로를 주지 않는다: code={}", code);
            throw new OdsayTooCloseException();
        }
        log.warn("ODsay 응답 에러: code={}", code);
        throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
    }
}
