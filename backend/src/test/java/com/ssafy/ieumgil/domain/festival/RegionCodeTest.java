package com.ssafy.ieumgil.domain.festival;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

    @Test
    @DisplayName("두 지역명이 함께 있으면 앞에 있는(= 더 넓은) 쪽을 고른다 — '경기도 광주'는 경기다")
    void picksTheLeftmostRegionWhenTwoNamesCollide() {
        // 주소는 넓은 곳부터 쓴다. 선언 순서로 고르면 '광주'가 '경기'보다 먼저 선언됐다는
        // 이유만으로 경기도 광주가 전남·광주(코드 12) 축제를 받아왔다.
        assertThat(RegionCode.findByName("경기도 광주")).contains(RegionCode.GYEONGGI);
        assertThat(RegionCode.findByName("광주광역시")).contains(RegionCode.GWANGJU);
        assertThat(RegionCode.findByName("전남 광주")).contains(RegionCode.JEONNAM);
    }

    @Test
    @DisplayName("시·군 이름만 오면 매칭에 실패하고 흔적을 남긴다 — 축제 tool이 조용히 빠지면 아무도 모른다")
    void unmatchedDestinationIsLogged() {
        Logger logger = (Logger) LoggerFactory.getLogger(RegionCode.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(RegionCode.findByName("강릉")).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getFormattedMessage()).contains("강릉");
                });
    }
}
