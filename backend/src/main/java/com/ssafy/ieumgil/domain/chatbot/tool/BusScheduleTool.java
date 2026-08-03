package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.BusScheduleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class BusScheduleTool {

    private final BusScheduleProvider busScheduleProvider;

    @Tool(description = """
            Call this when the user asks the express/intercity bus schedule between two regions.
            Just pass the departure and arrival names (e.g. "Seoul", "Gwangju").
            This is for looking up intercity/interregional long-distance schedules — for walking or taxi travel between two places within the same destination (accommodation, cafe, etc.), use the walking/taxi route tools instead.
            Returns up to 10 entries sorted by earliest departure time.
            """)
    public List<TransitScheduleResDTO.BusSchedule> getBusSchedule(String departureName, String arrivalName) {
        try {
            return busScheduleProvider.findSchedule(departureName, arrivalName).stream()
                    .sorted(Comparator.comparing(TransitScheduleResDTO.BusSchedule::departureTime))
                    .limit(10)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("bus schedule tool call failed: {} -> {}", departureName, arrivalName, e);
            return List.of();
        }
    }
}
