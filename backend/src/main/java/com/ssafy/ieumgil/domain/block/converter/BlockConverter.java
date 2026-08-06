package com.ssafy.ieumgil.domain.block.converter;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BlockConverter {

    /**
     * 생성 요청 → 엔티티. durationMin·isTimeFixed·budget이 null이면
     * 엔티티의 @Builder.Default(60/false/0)가 채우도록 조건부로만 넣는다.
     */
    public static Block toBlock(Project project, User author, String orderKey, BlockReqDTO.Create request) {
        Block.BlockBuilder builder = Block.builder()
                .project(project)
                .author(author)
                .lastEditedBy(author)
                .dayNo(request.dayNo())
                .orderKey(orderKey)
                .category(request.category())
                .subCategory(request.subCategory())
                .name(request.name())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .detail(request.detail())
                .lat(request.lat())
                .lng(request.lng())
                .placeId(request.placeId())
                .address(request.address())
                .vehicleFlag(request.vehicleFlag())
                .transportMeta(request.transportMeta())
                .source(request.source());

        if (request.durationMin() != null) {
            builder.durationMin(request.durationMin());
        }
        if (request.isTimeFixed() != null) {
            builder.isTimeFixed(request.isTimeFixed());
        }
        if (request.budget() != null) {
            builder.budget(request.budget());
        }
        return builder.build();
    }

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
                // 005 마이그레이션 이전 행은 null — 클라이언트가 authorId로 폴백한다
                .lastEditedById(block.getLastEditedBy() == null ? null : block.getLastEditedBy().getId())
                .fieldUpdatedAt(block.getFieldUpdatedAt())
                .createdAt(block.getCreatedAt())
                .build();
    }
}
