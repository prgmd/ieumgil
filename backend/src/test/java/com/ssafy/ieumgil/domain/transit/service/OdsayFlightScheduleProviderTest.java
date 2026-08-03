package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OdsayFlightScheduleProviderTest {

    @Mock
    private TransitScheduleQueryService transitScheduleQueryService;

    @Test
    void returnsScheduleWhenBothAirportsResolve() {
        TransitScheduleResDTO.FlightSchedule schedule = TransitScheduleResDTO.FlightSchedule.builder()
                .airline("대한항공").flightNo("KE1201").departureTime("08:00").arrivalTime("09:10").runDay("매일")
                .build();
        when(transitScheduleQueryService.getFlightSchedule(3500001, 3500003)).thenReturn(List.of(schedule));

        OdsayFlightScheduleProvider provider = new OdsayFlightScheduleProvider(transitScheduleQueryService);

        List<TransitScheduleResDTO.FlightSchedule> result = provider.findSchedule("김포", "제주");

        assertThat(result).containsExactly(schedule);
    }

    @Test
    void returnsEmptyListWhenDepartureAirportUnmatched() {
        OdsayFlightScheduleProvider provider = new OdsayFlightScheduleProvider(transitScheduleQueryService);

        assertThat(provider.findSchedule("존재하지않는공항", "제주")).isEmpty();
    }

    @Test
    void returnsEmptyListWhenArrivalAirportUnmatched() {
        OdsayFlightScheduleProvider provider = new OdsayFlightScheduleProvider(transitScheduleQueryService);

        assertThat(provider.findSchedule("김포", "존재하지않는공항")).isEmpty();
    }
}
