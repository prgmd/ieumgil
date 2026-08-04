package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.dto.OdsayBusTerminalResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TransitScheduleQueryServiceImpl implements TransitScheduleQueryService {

    private final OdsayClient odsayClient;

    @Override
    public Optional<TransitScheduleResDTO.TerminalSearchResult> searchTrainStation(String name) {
        return odsayClient.searchTrainTerminal(name).map(t -> TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(t.stationID())
                .stationName(t.stationName())
                .lat(t.y())
                .lng(t.x())
                .destinations(t.arrivalTerminals().stream()
                        .map(p -> TransitScheduleResDTO.Terminal.builder()
                                .stationId(p.stationID()).stationName(p.stationName())
                                .lat(p.y()).lng(p.x()).build())
                        .toList())
                .build());
    }

    @Override
    public Optional<TransitScheduleResDTO.TerminalSearchResult> searchExpressBusTerminal(String name) {
        return mapBusTerminal(odsayClient.searchExpressBusTerminal(name));
    }

    @Override
    public Optional<TransitScheduleResDTO.TerminalSearchResult> searchIntercityBusTerminal(String name) {
        return mapBusTerminal(odsayClient.searchIntercityBusTerminal(name));
    }

    private Optional<TransitScheduleResDTO.TerminalSearchResult> mapBusTerminal(
            Optional<OdsayBusTerminalResponse.Terminal> terminal) {
        return terminal.map(t -> TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(t.stationID())
                .stationName(t.stationName())
                .lat(t.y())
                .lng(t.x())
                .destinations(t.destinationTerminals().stream()
                        .map(p -> TransitScheduleResDTO.Terminal.builder()
                                .stationId(p.stationID()).stationName(p.stationName())
                                .lat(p.y()).lng(p.x()).build())
                        .toList())
                .build());
    }

    @Override
    public List<TransitScheduleResDTO.TrainSchedule> getTrainSchedule(int startStationId, int endStationId) {
        return odsayClient.getTrainSchedule(startStationId, endStationId).stream()
                .map(t -> TransitScheduleResDTO.TrainSchedule.builder()
                        .railName(t.railName())
                        .trainClass(t.trainClass())
                        .trainNo(t.trainNo())
                        .departureTime(t.departureTime())
                        .arrivalTime(t.arrivalTime())
                        .wasteTime(t.wasteTime())
                        .runDay(t.runDay())
                        .generalFare(resolveFare(t.fare() == null ? null : t.fare().general(), t.generalFare()))
                        .specialFare(resolveFare(t.fare() == null ? null : t.fare().special(), t.specialFare()))
                        .standingFare(resolveFare(t.fare() == null ? null : t.fare().standing(), t.standingFare()))
                        .build())
                .toList();
    }

    /**
     * ODsay는 열차 종류(SRT 등)에 따라 fare가 빈 객체({})로 오고 실제 요금은
     * generalFare/specialFare/standingFare의 요일별(weekday/weekend/holiday) 값에만 있는 경우가 있다.
     * fare 쪽 값이 없으면 요일별 값 중 있는 것을 순서대로(평일→주말→공휴일) 사용한다.
     * 둘 다 없으면 그 등급 좌석 자체가 없다는 뜻이므로 null(요금 미제공)로 둔다 — 0원으로 지어내지 않는다.
     */
    private Integer resolveFare(Integer primary, OdsayTrainScheduleResponse.DayFare breakdown) {
        if (primary != null) {
            return primary;
        }
        if (breakdown == null) {
            return null;
        }
        if (breakdown.weekday() != null) {
            return breakdown.weekday();
        }
        if (breakdown.weekend() != null) {
            return breakdown.weekend();
        }
        return breakdown.holiday();
    }

    @Override
    public List<TransitScheduleResDTO.BusSchedule> getIntercityBusSchedule(int startStationId, int endStationId) {
        return odsayClient.getIntercityBusSchedule(startStationId, endStationId).stream()
                .map(b -> TransitScheduleResDTO.BusSchedule.builder()
                        .busClass(b.busClass())
                        .departureTime(b.departureTime())
                        .wasteTimeMin(b.wasteTime())
                        .fare(b.fare())
                        .build())
                .toList();
    }

    @Override
    public List<TransitScheduleResDTO.FlightSchedule> getFlightSchedule(int startStationId, int endStationId) {
        return odsayClient.getFlightSchedule(startStationId, endStationId).stream()
                .map(f -> TransitScheduleResDTO.FlightSchedule.builder()
                        .airline(f.airline())
                        .flightNo(f.flight())
                        .departureTime(f.departureTime())
                        .arrivalTime(f.arrivalTime())
                        .runDay(f.runDay())
                        .build())
                .toList();
    }

    @Override
    public List<TransitScheduleResDTO.TrainSchedule> getTrainSchedule(
            int startStationId, int endStationId, LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return odsayClient.getTrainSchedule(startStationId, endStationId).stream()
                .filter(t -> ScheduleDayFilter.runsOn(t.runDay(), dayOfWeek))
                .map(t -> TransitScheduleResDTO.TrainSchedule.builder()
                        .railName(t.railName())
                        .trainClass(t.trainClass())
                        .trainNo(t.trainNo())
                        .departureTime(t.departureTime())
                        .arrivalTime(t.arrivalTime())
                        .wasteTime(t.wasteTime())
                        .runDay(t.runDay())
                        .generalFare(pickFare(t.fare() == null ? null : t.fare().general(), t.generalFare(), dayOfWeek))
                        .specialFare(pickFare(t.fare() == null ? null : t.fare().special(), t.specialFare(), dayOfWeek))
                        .standingFare(pickFare(t.fare() == null ? null : t.fare().standing(), t.standingFare(), dayOfWeek))
                        .build())
                .toList();
    }

    @Override
    public List<TransitScheduleResDTO.BusSchedule> getIntercityBusSchedule(
            int startStationId, int endStationId, LocalDate date) {
        // 버스 시간표에는 runDay가 없다 — ODsay가 주지 않으므로 필터할 것이 없다
        return getIntercityBusSchedule(startStationId, endStationId);
    }

    @Override
    public List<TransitScheduleResDTO.FlightSchedule> getFlightSchedule(
            int startStationId, int endStationId, LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return getFlightSchedule(startStationId, endStationId).stream()
                .filter(f -> ScheduleDayFilter.runsOn(f.runDay(), dayOfWeek))
                .toList();
    }

    /**
     * 요일별 breakdown이 있으면 그것을 우선한다.
     *
     * <p>날짜 없는 {@code resolveFare}는 {@code fare.general} 같은 통합 필드를 먼저 집는데,
     * 그 값이 어느 요일 기준인지 알 수 없다. 날짜를 아는 이 경로에서는 요일별 값이 더 정확하다.
     */
    private Integer pickFare(Integer flat, OdsayTrainScheduleResponse.DayFare breakdown, DayOfWeek dayOfWeek) {
        Integer byDay = ScheduleDayFilter.fareFor(breakdown, dayOfWeek);
        return byDay != null ? byDay : flat;
    }
}
