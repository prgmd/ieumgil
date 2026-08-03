package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OpinetClient;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpinetFuelPriceProviderTest {

    private static final int CONSTANT_PRICE = new ConstantFuelPriceProvider().pricePerLiter();

    @Mock
    private OpinetClient opinetClient;

    private OpinetFuelPriceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OpinetFuelPriceProvider(opinetClient, new ConstantFuelPriceProvider());
    }

    @Test
    @DisplayName("갱신에 성공하면 오피넷 유가를 쓴다")
    void refreshedPriceIsUsed() {
        when(opinetClient.fetchAverageGasolinePrice()).thenReturn(Optional.of(1867));

        provider.refresh();

        assertThat(provider.pricePerLiter()).isEqualTo(1867);
    }

    @Test
    @DisplayName("갱신 전에는 상수 유가를 쓰고, 요금 계산 경로에서 API를 부르지 않는다")
    void pricePerLiterNeverCallsApi() {
        // 캐시(=갱신된 값)만 읽는다. 요청마다 외부 호출을 하면 후보 계산이 오피넷 지연에 묶인다.
        assertThat(provider.pricePerLiter()).isEqualTo(CONSTANT_PRICE);
        assertThat(provider.pricePerLiter()).isEqualTo(CONSTANT_PRICE);
        assertThat(provider.pricePerLiter()).isEqualTo(CONSTANT_PRICE);

        verifyNoInteractions(opinetClient);
    }

    @Test
    @DisplayName("한 번 갱신하면 이후 조회는 캐시로 답한다")
    void cachedPriceDoesNotTriggerAnotherCall() {
        when(opinetClient.fetchAverageGasolinePrice()).thenReturn(Optional.of(1867));

        provider.refresh();
        provider.pricePerLiter();
        provider.pricePerLiter();

        verify(opinetClient, times(1)).fetchAverageGasolinePrice();
    }

    @Test
    @DisplayName("빈 응답(잘못된 키 등)이면 상수 유가로 폴백한다")
    void emptyResponseFallsBackToConstant() {
        when(opinetClient.fetchAverageGasolinePrice()).thenReturn(Optional.empty());

        provider.refresh();

        assertThat(provider.pricePerLiter()).isEqualTo(CONSTANT_PRICE);
    }

    @Test
    @DisplayName("호출이 실패하면 상수 유가로 폴백한다")
    void callFailureFallsBackToConstant() {
        when(opinetClient.fetchAverageGasolinePrice())
                .thenThrow(new TransitException(TransitErrorCode.OPINET_API_CALL_FAILED));

        provider.refresh();

        assertThat(provider.pricePerLiter()).isEqualTo(CONSTANT_PRICE);
    }

    @Test
    @DisplayName("갱신이 실패해도 마지막으로 성공한 값을 유지한다")
    void failedRefreshKeepsLastGoodPrice() {
        when(opinetClient.fetchAverageGasolinePrice())
                .thenReturn(Optional.of(1867))
                .thenThrow(new TransitException(TransitErrorCode.OPINET_API_CALL_FAILED));

        provider.refresh();
        provider.refresh();

        assertThat(provider.pricePerLiter()).isEqualTo(1867);
    }

    @Test
    @DisplayName("상식 범위를 벗어난 값은 채택하지 않는다")
    void insanePriceIsRejected() {
        // 단위가 바뀌는 사고(리터당 vs 배럴당, 소수점 처리 실수)를 그대로 요금에 태우면 안 된다.
        when(opinetClient.fetchAverageGasolinePrice()).thenReturn(Optional.of(31));

        provider.refresh();

        assertThat(provider.pricePerLiter()).isEqualTo(CONSTANT_PRICE);
    }
}
