package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OdsayTrainScheduleProviderTest {

    @Mock
    private TransitScheduleQueryService transitScheduleQueryService;

    @Test
    void returnsScheduleWhenBothStationsResolve() {
        TransitScheduleResDTO.TerminalSearchResult seoul = TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(1).stationName("서울").lat(37.5).lng(127.0).destinations(List.of()).build();
        TransitScheduleResDTO.TerminalSearchResult busan = TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(2).stationName("부산").lat(35.1).lng(129.0).destinations(List.of()).build();
        when(transitScheduleQueryService.searchTrainStation("서울")).thenReturn(Optional.of(seoul));
        when(transitScheduleQueryService.searchTrainStation("부산")).thenReturn(Optional.of(busan));
        TransitScheduleResDTO.TrainSchedule schedule = TransitScheduleResDTO.TrainSchedule.builder()
                .railName("KTX").trainClass("KTX").trainNo(101)
                .departureTime("09:00").arrivalTime("11:30").wasteTime("02:30").runDay("매일")
                .generalFare(59000).specialFare(null).standingFare(null)
                .build();
        when(transitScheduleQueryService.getTrainSchedule(1, 2)).thenReturn(List.of(schedule));

        OdsayTrainScheduleProvider provider = new OdsayTrainScheduleProvider(transitScheduleQueryService);

        List<TransitScheduleResDTO.TrainSchedule> result = provider.findSchedule("서울", "부산");

        assertThat(result).containsExactly(schedule);
    }

    @Test
    void returnsEmptyListWhenDepartureStationUnresolved() {
        when(transitScheduleQueryService.searchTrainStation("존재하지않는역")).thenReturn(Optional.empty());

        OdsayTrainScheduleProvider provider = new OdsayTrainScheduleProvider(transitScheduleQueryService);

        assertThat(provider.findSchedule("존재하지않는역", "부산")).isEmpty();
    }

    @Test
    void returnsEmptyListWhenArrivalStationUnresolved() {
        TransitScheduleResDTO.TerminalSearchResult seoul = TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(1).stationName("서울").lat(37.5).lng(127.0).destinations(List.of()).build();
        when(transitScheduleQueryService.searchTrainStation("서울")).thenReturn(Optional.of(seoul));
        when(transitScheduleQueryService.searchTrainStation("존재하지않는역")).thenReturn(Optional.empty());

        OdsayTrainScheduleProvider provider = new OdsayTrainScheduleProvider(transitScheduleQueryService);

        assertThat(provider.findSchedule("서울", "존재하지않는역")).isEmpty();
    }
}
