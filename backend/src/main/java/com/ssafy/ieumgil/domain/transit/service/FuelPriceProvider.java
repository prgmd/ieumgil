package com.ssafy.ieumgil.domain.transit.service;

/**
 * 연료비 추정에 쓰는 유가(원/L).
 *
 * <p>인터페이스로 감싼 이유는 오피넷(한국석유공사) 유가정보 API를 나중에 끼우기 위해서다.
 * 시간표 provider와 같은 패턴이며, 키 발급을 기다리느라 기능 전체가 막히지 않게 한다.
 */
public interface FuelPriceProvider {

    int pricePerLiter();
}
