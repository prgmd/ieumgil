package com.ssafy.ieumgil.domain.block.entity;

/**
 * 블록 카테고리.
 * SPOT·FOOD·STAY는 장소성 카테고리 — 생성 시 좌표(lat/lng)가 필수다.
 * TRANSPORT는 교통 블록으로 transportMeta를 갖는다.
 */
public enum BlockCategory {
    SPOT,
    FOOD,
    STAY,
    ETC,
    TRANSPORT;

    /** 장소성 카테고리 여부 — 좌표 필수 검증(BLK 생성)에 쓴다 */
    public boolean requiresCoordinate() {
        return this == SPOT || this == FOOD || this == STAY;
    }
}
