package com.ssafy.ieumgil.domain.block.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockConverterOffsetTest {

    private BlockReqDTO.Create create(Integer dayNo, LocalTime startTime) {
        return new BlockReqDTO.Create(
                BlockCategory.SPOT, "테스트", dayNo, "a", null, null, null, null,
                null, 60, startTime, null, false, 0, null, BlockSource.MANUAL, null, null);
    }

    @Test
    @DisplayName("Day 2 의 00:30 으로 만들면 오프셋 1470 이 채워진다")
    void offsetDerivedFromDayAndTime() {
        Block block = BlockConverter.toBlock(null, null, null, create(2, LocalTime.of(0, 30)));

        assertThat(block.getStartOffsetMinutes()).isEqualTo(1470);
    }

    @Test
    @DisplayName("Day 없이 만들면 후보(POOL)라 오프셋이 null 이다")
    void poolBlockHasNullOffset() {
        Block block = BlockConverter.toBlock(null, null, null, create(null, null));

        assertThat(block.getStartOffsetMinutes()).isNull();
        assertThat(block.isInPool()).isTrue();
    }

    @Test
    @DisplayName("Day 는 있는데 시각이 없으면 그 Day 의 00:00 으로 앉는다")
    void dayWithoutTimeLandsAtMidnight() {
        Block block = BlockConverter.toBlock(null, null, null, create(3, null));

        assertThat(block.getStartOffsetMinutes()).isEqualTo(2880);
    }
}
