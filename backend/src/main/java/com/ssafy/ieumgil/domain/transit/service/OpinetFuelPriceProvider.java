package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.client.OpinetClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 오피넷 전국 평균 휘발유가를 쓰는 유가 provider. 조회에 실패하면 상수로 폴백한다.
 *
 * <p>폴백 값은 {@link ConstantFuelPriceProvider}를 그대로 재사용한다 — 상수를 여기에 또 두면
 * 두 곳을 따로 갱신해야 하고, 한쪽만 고쳐서 값이 갈리는 것이 흔한 사고다.
 *
 * <p>연료비는 교통 후보의 부수 정보다. 유가 조회가 안 된다고 자차 후보 자체가 사라지면
 * 손해가 훨씬 크므로 실패는 warn 로그로 남기고 계산은 계속한다.
 *
 * <p>빈 등록은 {@code @ConditionalOnExpression}으로 갈린다. {@code @ConditionalOnProperty}는
 * <b>값이 빈 문자열이어도 매치</b>로 판정하는데, application.yaml이 {@code ${OPINET_API_KEY:}}로
 * 기본값을 비워 두므로 키 없는 환경(CI·신규 팀원)에서도 이 빈이 떠 버린다. 그래서 공백 여부를
 * 직접 본다. 키가 없으면 {@link ConstantFuelPriceProvider}가 유일한 provider가 되어 기동은 그대로 된다.
 */
@Slf4j
@Component
@Primary
@ConditionalOnExpression("!'${opinet.api-key:}'.isBlank()")
@RequiredArgsConstructor
public class OpinetFuelPriceProvider implements FuelPriceProvider {

    /**
     * 채택 가능한 유가 범위(원/L). 국내 휘발유가는 2000년대 이후 이 범위를 벗어난 적이 없다.
     *
     * <p>단위가 어긋나는 사고(리터당 vs 배럴당, 소수점 처리 실수)는 값이 "0보다 크다"는 검사를
     * 통과하면서 요금을 수십 배로 만든다 — 이 저장소에서 실제로 그런 버그가 배포까지 갔다.
     */
    private static final int MIN_SANE_PRICE = 500;
    private static final int MAX_SANE_PRICE = 5_000;

    private final OpinetClient opinetClient;
    private final ConstantFuelPriceProvider fallback;

    /** 마지막으로 성공한 조회값(원/L). 갱신은 스케줄러 스레드, 읽기는 요청 스레드라 volatile이다. */
    private volatile Integer cachedPricePerLiter;

    /**
     * 캐시만 읽는다 — 요금 계산 경로에서 외부 API를 부르면 후보 계산 전체가 오피넷 응답 시간에 묶인다.
     * 아직 한 번도 성공하지 못했으면 상수로 답한다.
     */
    @Override
    public int pricePerLiter() {
        Integer cached = cachedPricePerLiter;
        return cached != null ? cached : fallback.pricePerLiter();
    }

    /**
     * 유가 갱신. 하루 한 번 + 기동 직후 한 번 돈다.
     *
     * <p>기동 시에도 받는 이유: 개발 노트북은 새벽에 꺼져 있어 크론이 사실상 돌지 않는다
     * (축제 배치가 실제로 그래서 빈 상태로 며칠 있었다). 유가는 값 하나라 메모리 캐시로 충분하고,
     * 기동 시 조회가 있으니 재시작 후 상수로 답하는 구간도 짧다.
     *
     * <p>크론을 4시 정각이 아니라 20분으로 둔 이유는 축제 배치(4시 정각)와 겹치지 않게 하기
     * 위해서다 — {@code @Scheduled}는 기본이 단일 스레드라 같은 시각 작업이 서로를 밀어낸다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 20 4 * * *")
    public void refresh() {
        Optional<Integer> fetched;
        try {
            fetched = opinetClient.fetchAverageGasolinePrice();
        } catch (RuntimeException e) {
            log.warn("오피넷 유가 갱신 실패 — {}원/L을 계속 쓴다: {}", pricePerLiter(), e.getMessage());
            return;
        }
        if (fetched.isEmpty()) {
            log.warn("오피넷 유가를 얻지 못했다 — {}원/L을 계속 쓴다", pricePerLiter());
            return;
        }

        int price = fetched.get();
        if (price < MIN_SANE_PRICE || price > MAX_SANE_PRICE) {
            log.warn("오피넷 유가 {}원/L은 상식 범위({}~{})를 벗어나 채택하지 않는다 — {}원/L을 계속 쓴다",
                    price, MIN_SANE_PRICE, MAX_SANE_PRICE, pricePerLiter());
            return;
        }
        cachedPricePerLiter = price;
        log.info("오피넷 전국 평균 휘발유가 갱신: {}원/L", price);
    }
}
