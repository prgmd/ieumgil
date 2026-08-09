package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.DomesticAirport;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OdsayFlightScheduleProvider implements FlightScheduleProvider {

    private final TransitScheduleQueryService transitScheduleQueryService;

    @Override
    public List<TransitScheduleResDTO.FlightSchedule> findSchedule(String departureName, String arrivalName) {
        Optional<DomesticAirport> departure = DomesticAirport.findByName(departureName);
        Optional<DomesticAirport> arrival = DomesticAirport.findByName(arrivalName);
        if (departure.isEmpty() || arrival.isEmpty()) {
            return List.of();
        }
        return transitScheduleQueryService.getFlightSchedule(departure.get().stationId(), arrival.get().stationId());
    }
}
