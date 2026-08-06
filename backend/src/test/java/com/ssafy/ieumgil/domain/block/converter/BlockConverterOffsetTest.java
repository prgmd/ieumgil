package com.ssafy.ieumgil.domain.block.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.support.BlockFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BlockConverterOffsetTest {

    @Test
    @DisplayName("요청의 절대 오프셋이 그대로 엔티티에 실린다")
    void offsetCarriedThrough() {
        Block block = BlockConverter.toBlock(null, null, null,
                BlockFixtures.at(BlockCategory.SPOT, "테스트", "a", 1470));

        assertThat(block.getStartOffsetMinutes()).isEqualTo(1470);
    }

    @Test
    @DisplayName("오프셋 없이 만들면 후보(POOL)다")
    void poolBlockHasNullOffset() {
        Block block = BlockConverter.toBlock(null, null, null,
                BlockFixtures.pool(BlockCategory.SPOT, "테스트", "a"));

        assertThat(block.getStartOffsetMinutes()).isNull();
        assertThat(block.isInPool()).isTrue();
    }
}
