package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.TrainScheduleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrainScheduleToolTest {

    @Mock
    private TrainScheduleProvider trainScheduleProvider;

    @Test
    void returnsProviderResult() {
        TransitScheduleResDTO.TrainSchedule schedule = TransitScheduleResDTO.TrainSchedule.builder()
                .railName("KTX").trainClass("KTX").trainNo(101)
                .departureTime("09:00").arrivalTime("11:30").wasteTime("02:30").runDay("매일")
                .generalFare(59000).specialFare(null).standingFare(null)
                .build();
        when(trainScheduleProvider.findSchedule("서울", "부산")).thenReturn(List.of(schedule));

        TrainScheduleTool tool = new TrainScheduleTool(trainScheduleProvider);

        assertThat(tool.getTrainSchedule("서울", "부산")).containsExactly(schedule);
    }

    @Test
    void returnsEmptyListInsteadOfThrowingWhenProviderFails() {
        when(trainScheduleProvider.findSchedule(any(), any())).thenThrow(new RuntimeException("odsay down"));

        TrainScheduleTool tool = new TrainScheduleTool(trainScheduleProvider);

        assertThat(tool.getTrainSchedule("서울", "부산")).isEmpty();
    }
}
