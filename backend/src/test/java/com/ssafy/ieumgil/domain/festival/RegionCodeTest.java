package com.ssafy.ieumgil.domain.festival;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RegionCodeTest {

    @ParameterizedTest
    @CsvSource({
            "서울, 11",
            "서울특별시, 11",
            "부산, 26",
            "대구, 27",
            "인천, 28",
            "광주, 29",
            "대전, 30",
            "울산, 31",
            "세종, 36",
            "경기, 41",
            "강원, 42",
            "충북, 43",
            "충남, 44",
            "전북, 45",
            "전남, 46",
            "경북, 47",
            "경남, 48",
            "제주, 50",
            "제주도 2박3일, 50",
    })
    void findByNameResolvesRegionCode(String destination, String expectedCode) {
        Optional<RegionCode> result = RegionCode.findByName(destination);

        assertThat(result).isPresent();
        assertThat(result.get().code()).isEqualTo(expectedCode);
    }

    @Test
    void findByNameReturnsEmptyForUnmatchedDestination() {
        assertThat(RegionCode.findByName("도쿄")).isEmpty();
    }

    @Test
    void findByNameReturnsEmptyForBlankDestination() {
        assertThat(RegionCode.findByName("")).isEmpty();
    }

    @Test
    void allSeventeenRegionsHaveUniqueNonOverlappingNames() {
        for (RegionCode region : RegionCode.values()) {
            for (RegionCode other : RegionCode.values()) {
                if (region == other) {
                    continue;
                }
                assertThat(region.regionName()).doesNotContain(other.regionName());
            }
        }
    }
}
