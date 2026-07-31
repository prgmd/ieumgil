package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OdsayTrainScheduleProvider implements TrainScheduleProvider {

    private final TransitScheduleQueryService transitScheduleQueryService;

    @Override
    public List<TransitScheduleResDTO.TrainSchedule> findSchedule(String departureName, String arrivalName) {
        Optional<TransitScheduleResDTO.TerminalSearchResult> departure =
                transitScheduleQueryService.searchTrainStation(departureName);
        Optional<TransitScheduleResDTO.TerminalSearchResult> arrival =
                transitScheduleQueryService.searchTrainStation(arrivalName);
        if (departure.isEmpty() || arrival.isEmpty()) {
            return List.of();
        }
        return transitScheduleQueryService.getTrainSchedule(departure.get().stationId(), arrival.get().stationId());
    }
}
