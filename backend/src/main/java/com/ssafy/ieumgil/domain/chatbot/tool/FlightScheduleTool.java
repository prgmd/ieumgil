package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.FlightScheduleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class FlightScheduleTool {

    private final FlightScheduleProvider flightScheduleProvider;

    @Tool(description = """
            사용자가 두 지역 간 국내선 항공 시간표를 물을 때 호출한다.
            출발지명과 도착지명(예: "김포", "제주")을 그대로 전달하면 된다.
            도시/지역 간 장거리 이동 시간표 조회용이다 — 같은 목적지 안의 두 장소(숙소·카페 등) 사이
            도보·택시 이동은 이 툴 대신 도보/택시 경로 툴을 쓴다.
            """)
    public List<TransitScheduleResDTO.FlightSchedule> getFlightSchedule(String departureName, String arrivalName) {
        try {
            return flightScheduleProvider.findSchedule(departureName, arrivalName);
        } catch (RuntimeException e) {
            log.warn("flight schedule tool call failed: {} -> {}", departureName, arrivalName, e);
            return List.of();
        }
    }
}
