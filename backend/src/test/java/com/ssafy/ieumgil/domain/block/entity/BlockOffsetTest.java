package com.ssafy.ieumgil.domain.block.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockOffsetTest {

    private Block withOffset(Integer offset, int durationMin) {
        return Block.builder()
                .startOffsetMinutes(offset)
                .durationMin(durationMin)
                .build();
    }

    @Test
    @DisplayName("오프셋이 null 이면 후보(POOL)이고 파생값이 전부 null 이다")
    void nullOffsetMeansPool() {
        Block block = withOffset(null, 60);

        assertThat(block.isInPool()).isTrue();
        assertThat(block.dayNo()).isNull();
        assertThat(block.startMinuteOfDay()).isNull();
        assertThat(block.endOffsetMinutes()).isNull();
    }

    @Test
    @DisplayName("Day 1 첫 분은 dayNo 1 · 0분이다")
    void firstMinuteOfDayOne() {
        Block block = withOffset(0, 60);

        assertThat(block.isInPool()).isFalse();
        assertThat(block.dayNo()).isEqualTo(1);
        assertThat(block.startMinuteOfDay()).isZero();
    }

    @Test
    @DisplayName("Day 2 의 00:30 은 오프셋 1470 이다")
    void dayTwoHalfPastMidnight() {
        Block block = withOffset(1470, 60);

        assertThat(block.dayNo()).isEqualTo(2);
        assertThat(block.startMinuteOfDay()).isEqualTo(30);
    }

    @Test
    @DisplayName("자정을 넘는 블록의 종료는 되감기지 않고 다음 Day 로 이어진다")
    void endCrossesMidnightWithoutWrapping() {
        // Day 1 23:30 에 시작하는 330분(5시간 30분) 블록 → Day 2 05:00 종료
        Block block = withOffset(1410, 330);

        assertThat(block.dayNo()).isEqualTo(1);
        assertThat(block.startMinuteOfDay()).isEqualTo(23 * 60 + 30);
        assertThat(block.endOffsetMinutes()).isEqualTo(1740);
    }

    @Test
    @DisplayName("move 는 오프셋과 orderKey 를 함께 바꾸고, null 오프셋으로 POOL 에 보낸다")
    void moveSetsOffsetAndOrderKey() {
        Block block = withOffset(600, 60);

        block.move(1470, "b");
        assertThat(block.getStartOffsetMinutes()).isEqualTo(1470);
        assertThat(block.getOrderKey()).isEqualTo("b");

        block.move(null, "c");
        assertThat(block.isInPool()).isTrue();
        assertThat(block.getOrderKey()).isEqualTo("c");
    }
}
