package com.ssafy.ieumgil.domain.block.dto;

import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.entity.VehicleFlag;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    /** 편집 락 획득 응답. 실패 시 holder·잔여 TTL로 클라이언트 재시도 주기의 근거를 준다 */
    @Builder
    public record LockResult(
            boolean acquired,
            Long holder,
            long ttlRemaining
    ) {
    }

    /** 락 하트비트 응답 — TTL이 30초로 재설정됐음을 알린다 */
    @Builder
    public record LockHeartbeat(
            long ttlRemaining
    ) {
    }

    /**
     * 블록 한 개의 전체 표현 — 스냅샷(blocks[])이 쓴다.
     * 위치는 절대 오프셋 하나로만 표현한다 — Day 번호·시각은 클라이언트가 여기서 파생한다.
     */
    @Builder
    public record Item(
            Long blockId,
            @Schema(description = "Day 1 00:00 기준 경과 분. null 이면 후보(POOL)")
            Integer startOffsetMinutes,
            String orderKey,
            BlockCategory category,
            String subCategory,
            String name,
            Integer durationMin,
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
            Long lastEditedById,
            Map<String, String> fieldUpdatedAt,
            LocalDateTime createdAt
    ) {
    }
}
