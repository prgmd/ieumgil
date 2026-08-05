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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

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

    private final RestClient restClient;
    private final OdsayProperties properties;

    public OdsayClient(RestClient.Builder builder, OdsayProperties properties) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    public List<OdsayRouteResponse.Path> searchPublicTransitRoute(
            double startLat, double startLng, double endLat, double endLng, String mode) {
        try {
            int searchPathType = "BUS".equals(mode) ? 2 : "SUBWAY".equals(mode) ? 1 : 0;
            URI uri = URI.create(properties.baseUrl() + "/searchPubTransPathT"
                    + "?SX=" + startLng + "&SY=" + startLat
                    + "&EX=" + endLng + "&EY=" + endLat
                    + "&apiKey=" + properties.apiKey()
                    + "&SearchPathType=" + searchPathType);
            OdsayRouteResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayRouteResponse.class);
            checkRouteError(response);
            if (response == null || response.result() == null || response.result().path() == null) {
                return List.of();
            }
            return response.result().path();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("ODsay 대중교통 길찾기 실패: {}", e.getMessage());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }

    public Optional<OdsayTrainTerminalResponse.Terminal> searchTrainTerminal(String terminalName) {
        try {
            URI uri = URI.create(properties.baseUrl() + "/trainTerminals"
                    + "?terminalName=" + URLEncoder.encode(terminalName, StandardCharsets.UTF_8)
                    + "&apiKey=" + properties.apiKey());
            OdsayTrainTerminalResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayTrainTerminalResponse.class);
            checkForError(response == null ? null : response.error());
            if (response == null || response.result() == null || response.result().isEmpty()) {
                return Optional.empty();
            }
            return selectBestMatch(response.result(), terminalName,
                    OdsayTrainTerminalResponse.Terminal::stationName,
                    OdsayTrainTerminalResponse.Terminal::haveDestinationTerminals);
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("ODsay 기차역 검색 실패: {}", e.getMessage());
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
            OdsayBusTerminalResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayBusTerminalResponse.class);
            checkForError(response == null ? null : response.error());
            if (response == null || response.result() == null || response.result().isEmpty()) {
                return Optional.empty();
            }
            return selectBestMatch(response.result(), terminalName,
                    OdsayBusTerminalResponse.Terminal::stationName,
                    OdsayBusTerminalResponse.Terminal::haveDestinationTerminals);
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("ODsay 버스터미널 검색 실패: {}", e.getMessage());
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
        try {
            URI uri = URI.create(properties.baseUrl() + "/trainServiceTime"
                    + "?startStationID=" + startStationId
                    + "&endStationID=" + endStationId
                    + "&apiKey=" + properties.apiKey());
            OdsayTrainScheduleResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayTrainScheduleResponse.class);
            checkForError(response == null ? null : response.error());
            if (response == null || response.result() == null || response.result().station() == null) {
                return List.of();
            }
            return response.result().station();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("ODsay 기차 시간표 조회 실패: {}", e.getMessage());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }

    public List<OdsayBusScheduleResponse.Bus> getIntercityBusSchedule(int startStationId, int endStationId) {
        try {
            URI uri = URI.create(properties.baseUrl() + "/searchInterBusSchedule"
                    + "?startStationID=" + startStationId
                    + "&endStationID=" + endStationId
                    + "&apiKey=" + properties.apiKey());
            OdsayBusScheduleResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayBusScheduleResponse.class);
            checkForError(response == null ? null : response.error());
            if (response == null || response.result() == null || response.result().schedule() == null) {
                return List.of();
            }
            return response.result().schedule();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("ODsay 고속/시외버스 시간표 조회 실패: {}", e.getMessage());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
        }
    }

    public List<OdsayFlightScheduleResponse.Flight> getFlightSchedule(int startStationId, int endStationId) {
        try {
            URI uri = URI.create(properties.baseUrl() + "/airServiceTime"
                    + "?startStationID=" + startStationId
                    + "&endStationID=" + endStationId
                    + "&apiKey=" + properties.apiKey());
            OdsayFlightScheduleResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(OdsayFlightScheduleResponse.class);
            checkForError(response == null ? null : response.error());
            if (response == null || response.result() == null || response.result().station() == null) {
                return List.of();
            }
            return response.result().station();
        } catch (RestClientException | IllegalArgumentException e) {
            log.warn("ODsay 국내선 항공 시간표 조회 실패: {}", e.getMessage());
            throw new TransitException(TransitErrorCode.ODSAY_API_CALL_FAILED);
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
