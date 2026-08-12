package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.BusScheduleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BusScheduleTool {

    private final BusScheduleProvider busScheduleProvider;

    @Tool(description = """
            Call this when the user asks the express/intercity bus schedule between two regions.
            Just pass the departure and arrival names (e.g. "Seoul", "Gwangju").
            This is for looking up intercity/interregional long-distance schedules — for walking or taxi travel between two places within the same destination (accommodation, cafe, etc.), use the walking/taxi route tools instead.
            Returns up to 10 entries sorted by earliest departure time.
            """)
    public List<TransitScheduleResDTO.BusSchedule> getBusSchedule(String departureName, String arrivalName) {
        return ScheduleToolSupport.topByDepartureTime(departureName, arrivalName,
                () -> busScheduleProvider.findSchedule(departureName, arrivalName),
                TransitScheduleResDTO.BusSchedule::departureTime, "bus");
    }
}
