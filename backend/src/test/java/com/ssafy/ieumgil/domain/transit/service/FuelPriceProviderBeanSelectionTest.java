package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OpinetClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 유가 provider 선택 규칙 — 키가 있으면 오피넷, 없으면 상수.
 *
 * <p>CI와 신규 팀원 환경에는 오피넷 키가 없다. 그 환경에서 기동이 깨지지 않는지를 지키는 테스트다.
 */
class FuelPriceProviderBeanSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(OpinetClient.class, () -> mock(OpinetClient.class))
            .withUserConfiguration(ConstantFuelPriceProvider.class, OpinetFuelPriceProvider.class);

    @Test
    @DisplayName("키가 없으면 상수 provider만 뜬다")
    void withoutKeyOnlyConstantProviderIsRegistered() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpinetFuelPriceProvider.class);
            assertThat(context.getBean(FuelPriceProvider.class))
                    .isInstanceOf(ConstantFuelPriceProvider.class);
        });
    }

    @Test
    @DisplayName("키가 빈 문자열이어도(설정만 있고 값이 없는 환경) 상수 provider를 쓴다")
    void blankKeyFallsBackToConstantProvider() {
        // application.yaml이 ${OPINET_API_KEY:}로 기본값을 비워 두므로 "속성은 있고 값은 빈" 상태가
        // 키 없는 환경의 실제 모습이다. @ConditionalOnProperty는 이 경우를 매치로 판정한다.
        runner.withPropertyValues("opinet.api-key=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpinetFuelPriceProvider.class);
            assertThat(context.getBean(FuelPriceProvider.class))
                    .isInstanceOf(ConstantFuelPriceProvider.class);
        });
    }

    @Test
    @DisplayName("키가 있으면 오피넷 provider가 주 빈이 된다")
    void withKeyOpinetProviderIsPrimary() {
        runner.withPropertyValues("opinet.api-key=real-key").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FuelPriceProvider.class))
                    .isInstanceOf(OpinetFuelPriceProvider.class);
        });
    }
}
