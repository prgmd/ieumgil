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

    @Test
    void capsAtTenResultsSortedByDepartureTimeAscending() {
        List<TransitScheduleResDTO.TrainSchedule> manySchedules = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> TransitScheduleResDTO.TrainSchedule.builder()
                        .railName("KTX").trainClass("KTX").trainNo(i)
                        .departureTime(String.format("%02d:00", 23 - i)) // descending, so sort must reorder them
                        .arrivalTime("23:59").wasteTime("01:00").runDay("매일")
                        .generalFare(50000).specialFare(null).standingFare(null)
                        .build())
                .toList();
        when(trainScheduleProvider.findSchedule("서울", "부산")).thenReturn(manySchedules);

        TrainScheduleTool tool = new TrainScheduleTool(trainScheduleProvider);

        List<TransitScheduleResDTO.TrainSchedule> result = tool.getTrainSchedule("서울", "부산");

        assertThat(result).hasSize(10);
        assertThat(result).isSortedAccordingTo(java.util.Comparator.comparing(TransitScheduleResDTO.TrainSchedule::departureTime));
    }
}
