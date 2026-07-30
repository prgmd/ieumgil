package com.ssafy.ieumgil.domain.block.converter;

import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BlockConverter {

    public static BlockResDTO.Item toItem(Block block) {
        return BlockResDTO.Item.builder()
                .blockId(block.getId())
                .dayNo(block.getDayNo())
                .orderKey(block.getOrderKey())
                .category(block.getCategory())
                .subCategory(block.getSubCategory())
                .name(block.getName())
                .durationMin(block.getDurationMin())
                .startTime(block.getStartTime())
                .endTime(block.getEndTime())
                .isTimeFixed(block.getIsTimeFixed())
                .budget(block.getBudget())
                .detail(block.getDetail())
                .lat(block.getLat())
                .lng(block.getLng())
                .placeId(block.getPlaceId())
                .address(block.getAddress())
                .vehicleFlag(block.getVehicleFlag())
                .transportMeta(block.getTransportMeta())
                .source(block.getSource())
                // FK 값만 필요하므로 LAZY 프록시의 getId()로 읽는다 — 추가 쿼리가 나가지 않는다
                .authorId(block.getAuthor().getId())
                .fieldUpdatedAt(block.getFieldUpdatedAt())
                .createdAt(block.getCreatedAt())
                .build();
    }
}
