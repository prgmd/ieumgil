package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.TrainScheduleProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrainScheduleTool {

    private final TrainScheduleProvider trainScheduleProvider;

    @Tool(description = """
            사용자가 두 지역 간 기차(KTX 포함) 시간표를 물을 때 호출한다.
            출발지명과 도착지명(예: "서울", "부산")을 그대로 전달하면 된다.
            도시/지역 간 장거리 이동 시간표 조회용이다 — 같은 목적지 안의 두 장소(숙소·카페 등) 사이
            도보·택시 이동은 이 툴 대신 도보/택시 경로 툴을 쓴다.
            """)
    public List<TransitScheduleResDTO.TrainSchedule> getTrainSchedule(String departureName, String arrivalName) {
        try {
            return trainScheduleProvider.findSchedule(departureName, arrivalName);
        } catch (RuntimeException e) {
            log.warn("train schedule tool call failed: {} -> {}", departureName, arrivalName, e);
            return List.of();
        }
    }
}
