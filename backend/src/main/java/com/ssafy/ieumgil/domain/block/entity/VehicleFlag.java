package com.ssafy.ieumgil.domain.block.entity;

/**
 * 차량 구간 표식 — ETC 카테고리 전용 (주차·렌터카 인수/반납 등).
 * 역할은 "수단 선택의 기본값 제안"까지만이라, 이동·삭제되어도 기존 교통 블록에 영향이 없다.
 */
public enum VehicleFlag {
    START,
    END
}
