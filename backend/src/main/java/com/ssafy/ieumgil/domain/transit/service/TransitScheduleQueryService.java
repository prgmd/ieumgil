package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransitScheduleQueryService {

    Optional<TransitScheduleResDTO.TerminalSearchResult> searchTrainStation(String name);

    Optional<TransitScheduleResDTO.TerminalSearchResult> searchExpressBusTerminal(String name);

    Optional<TransitScheduleResDTO.TerminalSearchResult> searchIntercityBusTerminal(String name);

    List<TransitScheduleResDTO.TrainSchedule> getTrainSchedule(int startStationId, int endStationId);

    List<TransitScheduleResDTO.BusSchedule> getIntercityBusSchedule(int startStationId, int endStationId);

    List<TransitScheduleResDTO.FlightSchedule> getFlightSchedule(int startStationId, int endStationId);

    /**
     * 여행 날짜의 요일에 운행하는 편만 반환하고, 요금도 그 요일 기준으로 고른다.
     *
     * <p>날짜 없는 {@link #getTrainSchedule(int, int)}는 챗봇 툴이 쓴다 — 그쪽은 사용자가
     * 대화로 날짜를 좁히므로 필터가 필요 없다.
     */
    List<TransitScheduleResDTO.TrainSchedule> getTrainSchedule(int startStationId, int endStationId, LocalDate date);

    List<TransitScheduleResDTO.BusSchedule> getIntercityBusSchedule(int startStationId, int endStationId, LocalDate date);

    List<TransitScheduleResDTO.FlightSchedule> getFlightSchedule(int startStationId, int endStationId, LocalDate date);
}
