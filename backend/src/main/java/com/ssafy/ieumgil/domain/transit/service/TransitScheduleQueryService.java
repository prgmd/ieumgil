package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;

import java.util.List;
import java.util.Optional;

public interface TransitScheduleQueryService {

    Optional<TransitScheduleResDTO.TerminalSearchResult> searchTrainStation(String name);

    Optional<TransitScheduleResDTO.TerminalSearchResult> searchExpressBusTerminal(String name);

    Optional<TransitScheduleResDTO.TerminalSearchResult> searchIntercityBusTerminal(String name);

    List<TransitScheduleResDTO.TrainSchedule> getTrainSchedule(int startStationId, int endStationId);

    List<TransitScheduleResDTO.BusSchedule> getIntercityBusSchedule(int startStationId, int endStationId);

    List<TransitScheduleResDTO.FlightSchedule> getFlightSchedule(int startStationId, int endStationId);
}
