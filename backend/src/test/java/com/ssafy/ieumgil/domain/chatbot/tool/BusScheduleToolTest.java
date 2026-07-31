package com.ssafy.ieumgil.domain.chatbot.tool;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import com.ssafy.ieumgil.domain.transit.service.BusScheduleProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusScheduleToolTest {

    @Mock
    private BusScheduleProvider busScheduleProvider;

    @Test
    void returnsProviderResult() {
        TransitScheduleResDTO.BusSchedule schedule = TransitScheduleResDTO.BusSchedule.builder()
                .busClass(1).departureTime("10:00").wasteTimeMin(240).fare(25000).build();
        when(busScheduleProvider.findSchedule("서울", "광주")).thenReturn(List.of(schedule));

        BusScheduleTool tool = new BusScheduleTool(busScheduleProvider);

        assertThat(tool.getBusSchedule("서울", "광주")).containsExactly(schedule);
    }

    @Test
    void returnsEmptyListInsteadOfThrowingWhenProviderFails() {
        when(busScheduleProvider.findSchedule(any(), any())).thenThrow(new RuntimeException("odsay down"));

        BusScheduleTool tool = new BusScheduleTool(busScheduleProvider);

        assertThat(tool.getBusSchedule("서울", "광주")).isEmpty();
    }
}
