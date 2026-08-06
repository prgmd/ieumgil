package com.ssafy.ieumgil.support;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;

import java.math.BigDecimal;

/** 테스트용 블록 생성 요청 팩토리 — 위치 인자 16개를 테스트마다 나열하지 않으려고 둔다 */
public final class BlockFixtures {

    private BlockFixtures() {
    }

    /** 후보(POOL) 블록 생성 요청 — 오프셋이 없으니 보드에 놓이지 않는다 */
    public static BlockReqDTO.Create pool(BlockCategory category, String name, String orderKey) {
        return create(category, name, orderKey, null);
    }

    /** 보드의 특정 지점(Day 1 00:00 기준 경과 분)에 놓는 생성 요청 */
    public static BlockReqDTO.Create at(BlockCategory category, String name, String orderKey,
                                        int startOffsetMinutes) {
        return create(category, name, orderKey, startOffsetMinutes);
    }

    /** 나머지 필드는 엔티티 기본값(60분/false/0원)에 맡기는 최소 생성 요청 */
    public static BlockReqDTO.Create create(BlockCategory category, String name, String orderKey,
                                            Integer startOffsetMinutes) {
        return new BlockReqDTO.Create(
                category, name, startOffsetMinutes, orderKey,
                null, null, null, null, null, null,
                null, null, null, BlockSource.MANUAL, null, null);
    }

    /** 좌표·출처·세부 내용까지 지정하는 장소 블록 생성 요청 */
    public static BlockReqDTO.Create spot(String name, BigDecimal lat, BigDecimal lng,
                                          BlockSource source, String detail) {
        return new BlockReqDTO.Create(
                BlockCategory.SPOT, name, null, "a0",
                lat, lng, null, null, null, null,
                null, null, null, source, null, detail);
    }
}
