package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OdsayBusScheduleProvider implements BusScheduleProvider {

    private final TransitScheduleQueryService transitScheduleQueryService;

    @Override
    public List<TransitScheduleResDTO.BusSchedule> findSchedule(String departureName, String arrivalName) {
        Optional<TransitScheduleResDTO.TerminalSearchResult> departure = resolveTerminal(departureName);
        Optional<TransitScheduleResDTO.TerminalSearchResult> arrival = resolveTerminal(arrivalName);
        if (departure.isEmpty() || arrival.isEmpty()) {
            return List.of();
        }
        return transitScheduleQueryService.getIntercityBusSchedule(departure.get().stationId(), arrival.get().stationId());
    }

    /** 고속버스 터미널 먼저 시도, 못 찾으면 시외버스 터미널로 폴백 — ODsay 시간표 조회는 터미널 종류 구분 없이 ID만 필요하다. */
    private Optional<TransitScheduleResDTO.TerminalSearchResult> resolveTerminal(String name) {
        Optional<TransitScheduleResDTO.TerminalSearchResult> express =
                transitScheduleQueryService.searchExpressBusTerminal(name);
        if (express.isPresent()) {
            return express;
        }
        return transitScheduleQueryService.searchIntercityBusTerminal(name);
    }
}
