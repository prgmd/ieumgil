package com.ssafy.ieumgil.domain.transit.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DomesticAirportTest {

    @Test
    void 서울은_김포로_해석된다() {
        Optional<DomesticAirport> result = DomesticAirport.findByName("서울");

        assertThat(result).contains(DomesticAirport.GIMPO);
    }

    @Test
    void 김해는_부산_공항으로_해석된다() {
        Optional<DomesticAirport> result = DomesticAirport.findByName("김해");

        assertThat(result).contains(DomesticAirport.BUSAN);
    }

    @Test
    void 등록된_공항명은_그대로_해석된다_회귀_방지() {
        assertThat(DomesticAirport.findByName("부산")).contains(DomesticAirport.BUSAN);
        assertThat(DomesticAirport.findByName("김포")).contains(DomesticAirport.GIMPO);
        assertThat(DomesticAirport.findByName("제주")).contains(DomesticAirport.JEJU);
    }

    @Test
    void 공항이_없는_도시는_빈_값이다() {
        assertThat(DomesticAirport.findByName("대전")).isEmpty();
        assertThat(DomesticAirport.findByName("무안")).as("무안공항은 광주공항과 다른 공항이라 추측 매핑하지 않는다").isEmpty();
    }

    @Test
    void 이름이_null이거나_공백이면_빈_값이다() {
        assertThat(DomesticAirport.findByName(null)).isEmpty();
        assertThat(DomesticAirport.findByName(" 서울 ")).contains(DomesticAirport.GIMPO);
    }

    @Test
    void 공항_전체_명칭도_해석된다() {
        // 첫 매칭 시외 경로가 항공 경로 자신일 때 ODsay는 도시명이 아니라 이 전체 명칭을 준다
        assertThat(DomesticAirport.findByName("김해국제공항")).contains(DomesticAirport.BUSAN);
        assertThat(DomesticAirport.findByName("제주국제공항")).contains(DomesticAirport.JEJU);
        // 별칭이 아니라 등록명 자체인 도시도 접미사가 붙어 오면 마찬가지로 벗겨야 한다
        assertThat(DomesticAirport.findByName("김포국제공항")).contains(DomesticAirport.GIMPO);
    }

    @Test
    void 공항_전체_명칭이어도_등록되지_않은_도시는_빈_값이다() {
        assertThat(DomesticAirport.findByName("대전공항")).isEmpty();
        assertThat(DomesticAirport.findByName("무안국제공항"))
                .as("무안공항은 광주공항과 다른 공항이라 추측 매핑하지 않는다")
                .isEmpty();
    }

    @Test
    void 공항_전체_명칭도_공백에_안전하다() {
        assertThat(DomesticAirport.findByName("  김해국제공항  ")).contains(DomesticAirport.BUSAN);
    }
}
