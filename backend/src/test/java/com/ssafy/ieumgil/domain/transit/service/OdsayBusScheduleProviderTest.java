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
class OdsayBusScheduleProviderTest {

    @Mock
    private TransitScheduleQueryService transitScheduleQueryService;

    @Test
    void returnsScheduleWhenBothTerminalsResolveViaExpressBus() {
        TransitScheduleResDTO.TerminalSearchResult seoul = TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(1).stationName("서울고속버스터미널").lat(37.5).lng(127.0).destinations(List.of()).build();
        TransitScheduleResDTO.TerminalSearchResult gwangju = TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(2).stationName("광주고속버스터미널").lat(35.1).lng(126.9).destinations(List.of()).build();
        when(transitScheduleQueryService.searchExpressBusTerminal("서울")).thenReturn(Optional.of(seoul));
        when(transitScheduleQueryService.searchExpressBusTerminal("광주")).thenReturn(Optional.of(gwangju));
        TransitScheduleResDTO.BusSchedule schedule = TransitScheduleResDTO.BusSchedule.builder()
                .busClass(1).departureTime("10:00").wasteTimeMin(240).fare(25000).build();
        when(transitScheduleQueryService.getIntercityBusSchedule(1, 2)).thenReturn(List.of(schedule));

        OdsayBusScheduleProvider provider = new OdsayBusScheduleProvider(transitScheduleQueryService);

        List<TransitScheduleResDTO.BusSchedule> result = provider.findSchedule("서울", "광주");

        assertThat(result).containsExactly(schedule);
    }

    @Test
    void fallsBackToIntercityBusTerminalWhenExpressBusTerminalNotFound() {
        when(transitScheduleQueryService.searchExpressBusTerminal("작은마을")).thenReturn(Optional.empty());
        TransitScheduleResDTO.TerminalSearchResult smallTown = TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(3).stationName("작은마을시외버스터미널").lat(36.0).lng(127.5).destinations(List.of()).build();
        when(transitScheduleQueryService.searchIntercityBusTerminal("작은마을")).thenReturn(Optional.of(smallTown));
        TransitScheduleResDTO.TerminalSearchResult seoul = TransitScheduleResDTO.TerminalSearchResult.builder()
                .stationId(1).stationName("서울고속버스터미널").lat(37.5).lng(127.0).destinations(List.of()).build();
        when(transitScheduleQueryService.searchExpressBusTerminal("서울")).thenReturn(Optional.of(seoul));
        when(transitScheduleQueryService.getIntercityBusSchedule(3, 1)).thenReturn(List.of());

        OdsayBusScheduleProvider provider = new OdsayBusScheduleProvider(transitScheduleQueryService);

        List<TransitScheduleResDTO.BusSchedule> result = provider.findSchedule("작은마을", "서울");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyListWhenNeitherTerminalTypeResolves() {
        when(transitScheduleQueryService.searchExpressBusTerminal("존재하지않는곳")).thenReturn(Optional.empty());
        when(transitScheduleQueryService.searchIntercityBusTerminal("존재하지않는곳")).thenReturn(Optional.empty());

        OdsayBusScheduleProvider provider = new OdsayBusScheduleProvider(transitScheduleQueryService);

        assertThat(provider.findSchedule("존재하지않는곳", "서울")).isEmpty();
    }
}
