package com.ssafy.ieumgil.domain.transit.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class TransitCandidateReqDTO {

    @Schema(description = "교통 후보 계산 요청")
    public record Calculate(
            /*
             * 체인 순서대로 보낸다. 서버가 연속 쌍을 만든다 — [101,105,107]이면 (101,105),(105,107).
             * dayNo를 따로 받지 않는 이유는 blockId가 이미 모든 정보를 담고 있어서다.
             * 상한 30은 3일 일정에 Day당 10블록을 기준으로 잡았다 — 없으면 한 요청으로
             * 외부 API 쿼터를 소모할 수 있다.
             */
            @Schema(description = "구간을 만들 블록 id 목록 (체인 순서)", example = "[101, 105, 107]")
            @NotNull(message = "blockIds는 필수입니다.")
            @Size(max = 30, message = "한 번에 30개까지만 계산할 수 있습니다.")
            List<Long> blockIds
    ) {
    }
}
