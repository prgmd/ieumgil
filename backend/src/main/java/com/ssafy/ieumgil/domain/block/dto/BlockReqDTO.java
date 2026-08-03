package com.ssafy.ieumgil.domain.block.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.entity.VehicleFlag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

public class BlockReqDTO {

    /**
     * 블록 생성 (POST /api/projects/{projectId}/blocks).
     * 장소성 카테고리의 좌표 필수, vehicleFlag의 ETC 전용 규칙은 카테고리와의
     * 교차 검증이라 애노테이션으로 못 걸고 서비스에서 검증한다.
     */
    public record Create(
            @Schema(description = "카테고리", example = "SPOT")
            @NotNull(message = "카테고리는 필수입니다.")
            BlockCategory category,

            @Schema(description = "블록 이름", example = "성산일출봉")
            @NotBlank(message = "블록 이름은 필수입니다.")
            @Size(max = 255, message = "블록 이름은 255자 이하여야 합니다.")
            String name,

            @Schema(description = "Day 번호. null이면 후보(POOL)", example = "1")
            Integer dayNo,

            @Schema(description = "정렬 키. 미지정 시 서버가 말단 키 부여", example = "a0")
            @Size(max = 255)
            String orderKey,

            @Schema(description = "위도 (장소성 카테고리 필수)", example = "33.4581")
            BigDecimal lat,

            @Schema(description = "경도 (장소성 카테고리 필수)", example = "126.9425")
            BigDecimal lng,

            @Size(max = 64) String placeId,
            @Size(max = 255) String address,
            @Size(max = 50) String subCategory,

            @Schema(description = "소요시간(분), 기본 60", example = "90")
            @Positive(message = "소요시간은 1분 이상이어야 합니다.")
            Integer durationMin,

            @Schema(description = "시작 시각", example = "09:00")
            @JsonFormat(pattern = "HH:mm") LocalTime startTime,

            @Schema(description = "종료 시각", example = "10:30")
            @JsonFormat(pattern = "HH:mm") LocalTime endTime,

            @Schema(description = "시각 고정(앵커) 여부, 기본 false")
            Boolean isTimeFixed,

            @Schema(description = "예산(원) — 프로젝트 총액 기준, 기본 0", example = "5000")
            @PositiveOrZero(message = "예산은 0 이상이어야 합니다.")
            Integer budget,

            @Schema(description = "차량 구간 표식 — ETC 전용")
            VehicleFlag vehicleFlag,

            @Schema(description = "생성 출처", example = "KAKAO")
            @NotNull(message = "생성 출처는 필수입니다.")
            BlockSource source,

            @Schema(description = "교통 블록 전용 메타")
            Map<String, Object> transportMeta,

            @Schema(description = "세부 내용. 생성 시점에 아는 정보를 담는다", example = "개최 기간: 2026-10-04 ~ 2026-10-14")
            @Size(max = 500, message = "세부 내용은 500자 이하여야 합니다.")
            String detail
    ) {
    }

    /** LWW 필드 변경 한 건. value 타입은 필드마다 달라 서비스에서 파싱·검증한다 */
    public record FieldChange(
            @NotBlank(message = "field는 필수입니다.")
            String field,
            Object value
    ) {
    }

    /** 필드 단위 LWW 배치 갱신 (PATCH /api/blocks/{blockId}/fields) */
    public record UpdateFields(
            @NotEmpty(message = "변경할 필드가 없습니다.")
            List<@Valid FieldChange> fields
    ) {
    }

    /** 블록 이동 (PATCH /api/blocks/{blockId}/position). dayNo null = 후보(POOL)로 이동 */
    public record Move(
            Integer dayNo,

            @NotBlank(message = "orderKey는 필수입니다.")
            @Size(max = 255)
            String orderKey
    ) {
    }
}
