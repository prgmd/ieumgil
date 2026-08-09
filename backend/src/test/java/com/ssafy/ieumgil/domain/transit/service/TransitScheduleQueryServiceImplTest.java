package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OdsayClient;
import com.ssafy.ieumgil.domain.transit.dto.OdsayBusScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayBusTerminalResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayFlightScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainScheduleResponse;
import com.ssafy.ieumgil.domain.transit.dto.OdsayTrainTerminalResponse;
import com.ssafy.ieumgil.domain.transit.dto.TransitScheduleResDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransitScheduleQueryServiceImplTest {

    /** 토요일 — 평일/주말 갈림이 드러나는 날짜다 */
    private static final LocalDate SATURDAY = LocalDate.of(2026, 8, 8);

    @Mock
    private OdsayClient odsayClient;

    private TransitScheduleQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransitScheduleQueryServiceImpl(odsayClient);
    }

    @Test
    void searchTrainStationMapsToTerminalSearchResult() {
        OdsayTrainTerminalResponse.Terminal terminal = new OdsayTrainTerminalResponse.Terminal(
                3300128, "서울", 126.97, 37.55, true,
                List.of(new OdsayTrainTerminalResponse.Point(3300108, "부산", 129.04, 35.11)));
        when(odsayClient.searchTrainTerminal("서울")).thenReturn(Optional.of(terminal));

        Optional<TransitScheduleResDTO.TerminalSearchResult> result = service.searchTrainStation("서울");

        assertThat(result).isPresent();
        assertThat(result.get().stationId()).isEqualTo(3300128);
        assertThat(result.get().stationName()).isEqualTo("서울");
        assertThat(result.get().lat()).isEqualTo(37.55);
        assertThat(result.get().lng()).isEqualTo(126.97);
        assertThat(result.get().destinations()).hasSize(1);
        assertThat(result.get().destinations().get(0).stationName()).isEqualTo("부산");
    }

    @Test
    void searchExpressBusTerminalMapsToTerminalSearchResult() {
        OdsayBusTerminalResponse.Terminal terminal = new OdsayBusTerminalResponse.Terminal(
                4000057, "서울고속버스터미널", 127.00, 37.50, true,
                List.of(new OdsayBusTerminalResponse.Point(4000156, "부산종합버스터미널", 129.09, 35.28)));
        when(odsayClient.searchExpressBusTerminal("서울")).thenReturn(Optional.of(terminal));

        Optional<TransitScheduleResDTO.TerminalSearchResult> result = service.searchExpressBusTerminal("서울");

        assertThat(result).isPresent();
        assertThat(result.get().stationName()).isEqualTo("서울고속버스터미널");
        assertThat(result.get().destinations()).hasSize(1);
    }

    @Test
    void searchIntercityBusTerminalMapsToTerminalSearchResult() {
        OdsayBusTerminalResponse.Terminal terminal = new OdsayBusTerminalResponse.Terminal(
                4000035, "동서울종합터미널", 127.09, 37.53, true,
                List.of(new OdsayBusTerminalResponse.Point(3600005, "가산정류소", 127.19, 37.85)));
        when(odsayClient.searchIntercityBusTerminal("동서울")).thenReturn(Optional.of(terminal));

        Optional<TransitScheduleResDTO.TerminalSearchResult> result = service.searchIntercityBusTerminal("동서울");

        assertThat(result).isPresent();
        assertThat(result.get().destinations()).hasSize(1);
    }

    @Test
    void getTrainScheduleMapsFareAndTimes() {
        OdsayTrainScheduleResponse.Fare fare = new OdsayTrainScheduleResponse.Fare(59800, 83700, 50830);
        when(odsayClient.getTrainSchedule(3300128, 3300108))
                .thenReturn(List.of(new OdsayTrainScheduleResponse.Train(
                        "KTX경부선", "KTX", 1, "05:13", "07:50", "02:37", "매일", fare, null, null, null)));

        List<TransitScheduleResDTO.TrainSchedule> result = service.getTrainSchedule(3300128, 3300108);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).railName()).isEqualTo("KTX경부선");
        assertThat(result.get(0).departureTime()).isEqualTo("05:13");
        assertThat(result.get(0).generalFare()).isEqualTo(59800);
    }

    @Test
    void getTrainScheduleFallsBackToDayFareWhenFareIsEmpty() {
        OdsayTrainScheduleResponse.Fare emptyFare = new OdsayTrainScheduleResponse.Fare(null, null, null);
        OdsayTrainScheduleResponse.DayFare generalDayFare = new OdsayTrainScheduleResponse.DayFare(null, 22900, 23100);
        when(odsayClient.getTrainSchedule(3300128, 3300108))
                .thenReturn(List.of(new OdsayTrainScheduleResponse.Train(
                        "경부선", "SRT", 305, "07:30", "09:47", "02:17", "매일",
                        emptyFare, generalDayFare, null, null)));

        List<TransitScheduleResDTO.TrainSchedule> result = service.getTrainSchedule(3300128, 3300108);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).generalFare()).isEqualTo(22900);
        assertThat(result.get(0).specialFare()).isNull();
    }

    @Test
    void getIntercityBusScheduleMapsFareAndTimes() {
        when(odsayClient.getIntercityBusSchedule(4000057, 4000156))
                .thenReturn(List.of(new OdsayBusScheduleResponse.Bus(2, "06:00", 240, 39700)));

        List<TransitScheduleResDTO.BusSchedule> result = service.getIntercityBusSchedule(4000057, 4000156);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fare()).isEqualTo(39700);
        assertThat(result.get(0).wasteTimeMin()).isEqualTo(240);
    }

    @Test
    @DisplayName("고속버스 요금·소요시간이 없으면 0이 아니라 null이다")
    void 고속버스_요금이_없으면_널이다() {
        when(odsayClient.getIntercityBusSchedule(4000057, 4000156))
                .thenReturn(List.of(new OdsayBusScheduleResponse.Bus(1, "06:00", null, null)));

        List<TransitScheduleResDTO.BusSchedule> result = service.getIntercityBusSchedule(4000057, 4000156);

        assertThat(result).hasSize(1);
        // 0원으로 읽히면 "고속버스 0원 · 확정"이 되어 나간다
        assertThat(result.get(0).fare()).isNull();
        assertThat(result.get(0).wasteTimeMin()).isNull();
    }

    @Test
    @DisplayName("날짜를 주면 그 요일에 운행하지 않는 편은 뺀다")
    void 날짜를_주면_운행하지_않는_편을_뺀다() {
        OdsayTrainScheduleResponse.Fare fare = new OdsayTrainScheduleResponse.Fare(59800, 83700, 50830);
        when(odsayClient.getTrainSchedule(3300128, 3300108))
                .thenReturn(List.of(
                        new OdsayTrainScheduleResponse.Train(
                                "경부선", "KTX", 1, "05:13", "07:50", "02:37", "평일", fare, null, null, null),
                        new OdsayTrainScheduleResponse.Train(
                                "경부선", "KTX", 15, "06:00", "08:37", "02:37", "매일", fare, null, null, null)));

        List<TransitScheduleResDTO.TrainSchedule> result =
                service.getTrainSchedule(3300128, 3300108, SATURDAY);

        assertThat(result).extracting(TransitScheduleResDTO.TrainSchedule::trainNo).containsExactly(15);
    }

    @Test
    @DisplayName("주말 여행에는 주말 요금을 고른다 — 날짜 없는 조회는 평일 요금을 먼저 집는다")
    void 주말_여행에는_주말_요금을_고른다() {
        OdsayTrainScheduleResponse.Fare emptyFare = new OdsayTrainScheduleResponse.Fare(null, null, null);
        OdsayTrainScheduleResponse.DayFare generalDayFare =
                new OdsayTrainScheduleResponse.DayFare(22900, 25400, 26100);
        when(odsayClient.getTrainSchedule(3300128, 3300108))
                .thenReturn(List.of(new OdsayTrainScheduleResponse.Train(
                        "경부선", "SRT", 305, "07:30", "09:47", "02:17", "매일",
                        emptyFare, generalDayFare, null, null)));

        assertThat(service.getTrainSchedule(3300128, 3300108, SATURDAY).get(0).generalFare())
                .isEqualTo(25400);
        assertThat(service.getTrainSchedule(3300128, 3300108).get(0).generalFare())
                .isEqualTo(22900);
    }

    @Test
    @DisplayName("항공도 운행 요일로 거른다")
    void 항공도_운행_요일로_거른다() {
        when(odsayClient.getFlightSchedule(3500001, 3500003))
                .thenReturn(List.of(
                        new OdsayFlightScheduleResponse.Flight("에어서울", "06:00", "07:15", "RS901", "평일"),
                        new OdsayFlightScheduleResponse.Flight("대한항공", "07:00", "08:15", "KE1231", "매일")));

        List<TransitScheduleResDTO.FlightSchedule> result =
                service.getFlightSchedule(3500001, 3500003, SATURDAY);

        assertThat(result).extracting(TransitScheduleResDTO.FlightSchedule::flightNo).containsExactly("KE1231");
    }

    @Test
    @DisplayName("고속버스 시간표에는 운행 요일이 없어 날짜를 줘도 그대로다")
    void 고속버스는_날짜로_거르지_않는다() {
        when(odsayClient.getIntercityBusSchedule(4000057, 4000156))
                .thenReturn(List.of(new OdsayBusScheduleResponse.Bus(2, "06:00", 240, 39700)));

        assertThat(service.getIntercityBusSchedule(4000057, 4000156, SATURDAY)).hasSize(1);
    }

    @Test
    void getFlightScheduleMapsAirlineAndTimes() {
        when(odsayClient.getFlightSchedule(3500001, 3500003))
                .thenReturn(List.of(new OdsayFlightScheduleResponse.Flight(
                        "에어서울", "06:00", "07:15", "RS901", "매일")));

        List<TransitScheduleResDTO.FlightSchedule> result = service.getFlightSchedule(3500001, 3500003);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).airline()).isEqualTo("에어서울");
        assertThat(result.get(0).flightNo()).isEqualTo("RS901");
    }
}
