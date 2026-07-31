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
}
