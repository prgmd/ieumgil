package com.ssafy.ieumgil.domain.chatbot.dto;

import com.ssafy.ieumgil.domain.chatbot.ChatbotMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ChatbotReqDTO {

    @Schema(description = "챗봇 메시지 전송 요청")
    public record SendMessage(
            @Schema(description = "사용자 메시지", example = "제주도 2박3일 여행지 하나만 추천해줘")
            @NotBlank(message = "메시지는 필수입니다.") @Size(max = 2000, message = "메시지는 2000자를 넘을 수 없습니다.")
            String message,

            @Schema(description = "대화 모드. 미지정이면 GENERAL", example = "GENERAL")
            ChatbotMode mode,

            @Schema(description = "지도 뷰포트. mode가 MAP이면 필수, GENERAL이면 무시된다")
            @Valid MapContext mapContext,

            @Schema(description = "사용자가 지금 보고 있는 Day 번호(1부터). 없으면 안 보낸 것으로 본다",
                    example = "2")
            @Min(value = 1, message = "Day 번호는 1 이상이어야 합니다.")
            Integer dayNo
    ) {

        /** mode 미지정(구 클라이언트)은 GENERAL로 취급한다 */
        public ChatbotMode modeOrDefault() {
            return mode == null ? ChatbotMode.GENERAL : mode;
        }
    }

    /**
     * 사용자가 보고 있는 지도 범위. 카카오맵 SDK {@code map.getBounds()}의
     * {@code getSouthWest()}/{@code getNorthEast()}를 그대로 보낸다.
     *
     * <p>중심 좌표와 배율로 역산하지 않는 이유는 SDK가 bounds를 직접 주고, 카카오 로컬
     * 검색의 rect 파라미터도 남서·북동 좌표를 그대로 받기 때문이다.
     */
    @Schema(description = "지도 뷰포트 (남서·북동 좌표)")
    public record MapContext(
            @Schema(description = "남서 위도", example = "33.44")
            @NotNull(message = "남서 위도는 필수입니다.")
            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
            Double swLat,

            @Schema(description = "남서 경도", example = "126.93")
            @NotNull(message = "남서 경도는 필수입니다.")
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
            Double swLng,

            @Schema(description = "북동 위도", example = "33.47")
            @NotNull(message = "북동 위도는 필수입니다.")
            @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
            Double neLat,

            @Schema(description = "북동 경도", example = "126.95")
            @NotNull(message = "북동 경도는 필수입니다.")
            @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
            Double neLng
    ) {
    }
}
