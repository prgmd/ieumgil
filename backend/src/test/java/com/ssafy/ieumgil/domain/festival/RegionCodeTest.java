package com.ssafy.ieumgil.domain.festival;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지역코드는 TourAPI가 실제로 보내는 값이어야 한다.
 *
 * <p>틀리면 예외가 아니라 <b>조회 0건</b>으로 조용히 실패한다. 실제로 강원·세종·광주·전남이
 * 데이터가 있는데도 0건을 반환하고 있었다(2026-08-02 발견). 표준 법정동 코드와 다른 값들이라
 * "정리"하려다 되돌리기 쉬우므로 근거와 함께 테스트로 못을 박는다.
 */
class RegionCodeTest {

    @Test
    @DisplayName("특별자치도 개편이 반영된 코드를 쓴다 — 예전 코드로는 조회되지 않는다")
    void usesReorganizedProvinceCodes() {
        // 강원특별자치도. 예전 42가 아니다.
        assertThat(RegionCode.GANGWON.code()).isEqualTo("51");
        // 전북특별자치도. 예전 45가 아니다.
        assertThat(RegionCode.JEONBUK.code()).isEqualTo("52");
    }

    @Test
    @DisplayName("세종은 시도 필드에 시군구 코드가 온다 — 36이 아니라 36110")
    void sejongUsesDistrictLevelCode() {
        assertThat(RegionCode.SEJONG.code()).isEqualTo("36110");
    }

    @Test
    @DisplayName("광주와 전남은 한 코드로 합쳐져 온다 — 어느 쪽으로 물어도 같은 결과가 나온다")
    void gwangjuAndJeonnamShareOneCode() {
        assertThat(RegionCode.GWANGJU.code()).isEqualTo("12");
        assertThat(RegionCode.JEONNAM.code()).isEqualTo(RegionCode.GWANGJU.code());
    }

    @Test
    @DisplayName("목적지 문장에서 지역명을 찾는다")
    void findsRegionInsideDestinationText() {
        assertThat(RegionCode.findByName("강원도 강릉")).contains(RegionCode.GANGWON);
        assertThat(RegionCode.findByName("제주")).contains(RegionCode.JEJU);
        assertThat(RegionCode.findByName("도쿄")).isEmpty();
        assertThat(RegionCode.findByName(null)).isEmpty();
        assertThat(RegionCode.findByName("  ")).isEmpty();
    }
}
