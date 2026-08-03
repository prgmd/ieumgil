package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.FlightScheduleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightScheduleToolTest {

    @Mock
    private FlightScheduleProvider flightScheduleProvider;

    @Test
    void returnsProviderResult() {
        TransitScheduleResDTO.FlightSchedule schedule = TransitScheduleResDTO.FlightSchedule.builder()
                .airline("대한항공").flightNo("KE1201").departureTime("08:00").arrivalTime("09:10").runDay("매일")
                .build();
        when(flightScheduleProvider.findSchedule("김포", "제주")).thenReturn(List.of(schedule));

        FlightScheduleTool tool = new FlightScheduleTool(flightScheduleProvider);

        assertThat(tool.getFlightSchedule("김포", "제주")).containsExactly(schedule);
    }

    @Test
    void returnsEmptyListInsteadOfThrowingWhenProviderFails() {
        when(flightScheduleProvider.findSchedule(any(), any())).thenThrow(new RuntimeException("odsay down"));

        FlightScheduleTool tool = new FlightScheduleTool(flightScheduleProvider);

        assertThat(tool.getFlightSchedule("김포", "제주")).isEmpty();
    }

    @Test
    void capsAtTenResultsSortedByDepartureTimeAscending() {
        List<TransitScheduleResDTO.FlightSchedule> manySchedules = java.util.stream.IntStream.range(0, 15)
                .mapToObj(i -> TransitScheduleResDTO.FlightSchedule.builder()
                        .airline("대한항공").flightNo("KE" + (1200 + i))
                        .departureTime(String.format("%02d:00", 23 - i)) // descending, so sort must reorder them
                        .arrivalTime("23:59").runDay("매일")
                        .build())
                .toList();
        when(flightScheduleProvider.findSchedule("김포", "제주")).thenReturn(manySchedules);

        FlightScheduleTool tool = new FlightScheduleTool(flightScheduleProvider);

        List<TransitScheduleResDTO.FlightSchedule> result = tool.getFlightSchedule("김포", "제주");

        assertThat(result).hasSize(10);
        assertThat(result).isSortedAccordingTo(java.util.Comparator.comparing(TransitScheduleResDTO.FlightSchedule::departureTime));
    }
}
