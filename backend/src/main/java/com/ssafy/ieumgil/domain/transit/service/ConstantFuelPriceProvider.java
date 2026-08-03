package com.ssafy.ieumgil.domain.transit.service;

import org.springframework.stereotype.Component;

/**
 * 고정 유가.
 *
 * <p>오피넷 연동 전까지 쓰는 기본 구현이다. 유가는 주 1~2% 수준으로 움직여서 상수로 둬도
 * 연료비 오차가 몇백 원이다. 실제 오차는 연비 가정(차종을 모른다)에서 오므로, 유가를
 * 실시간으로 받는다고 정밀도가 크게 오르지는 않는다 — 그래서 이 값이 ESTIMATE로 표시된다.
 */
@Component
public class ConstantFuelPriceProvider implements FuelPriceProvider {

    /** 전국 평균 휘발유가. 출처: 오피넷, 2026-07-26~08-01 주간 평균(조회일 2026-08-03). 갱신 시 기준 기간·조회일 모두 함께 고칠 것. */
    private static final int PRICE_PER_LITER = 1869;

    @Override
    public int pricePerLiter() {
        return PRICE_PER_LITER;
    }
}
