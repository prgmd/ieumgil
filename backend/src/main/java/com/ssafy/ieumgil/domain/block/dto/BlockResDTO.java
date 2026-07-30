package com.ssafy.ieumgil.domain.block.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.entity.VehicleFlag;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

public class BlockResDTO {

    /** 생성 응답. seq는 이 op의 순번 — 클라이언트가 자기 변경의 위치를 안다 */
    @Builder
    public record Created(
            Long blockId,
            long seq
    ) {
    }

    /** 이동 응답 */
    @Builder
    public record Moved(
            Long blockId,
            long seq
    ) {
    }

    /**
     * LWW 배치 갱신 응답. false = 더 최신 값이 이미 반영돼 있어 무시됨(스테일).
     * 에러가 아니다 — 클라이언트는 이후 도착하는 op(또는 재조회)로 최신 값을 받는다.
     */
    @Builder
    public record FieldsApplied(
            Map<String, Boolean> applied
    ) {
    }

    /**
     * 블록 한 개의 전체 표현 — 스냅샷(blocks[])이 쓴다.
     * 시각은 API 문서 예시("09:00")에 맞춰 HH:mm으로 직렬화한다.
     */
    @Builder
    public record Item(
            Long blockId,
            Integer dayNo,
            String orderKey,
            BlockCategory category,
            String subCategory,
            String name,
            Integer durationMin,
            @JsonFormat(pattern = "HH:mm") LocalTime startTime,
            @JsonFormat(pattern = "HH:mm") LocalTime endTime,
            Boolean isTimeFixed,
            Integer budget,
            String detail,
            BigDecimal lat,
            BigDecimal lng,
            String placeId,
            String address,
            VehicleFlag vehicleFlag,
            Map<String, Object> transportMeta,
            BlockSource source,
            Long authorId,
            Map<String, String> fieldUpdatedAt,
            LocalDateTime createdAt
    ) {
    }
}
