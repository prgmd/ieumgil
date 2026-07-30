package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.dto.OdsayBusTerminalResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
}
