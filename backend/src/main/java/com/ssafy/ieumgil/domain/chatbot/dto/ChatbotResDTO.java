package com.ssafy.ieumgil.domain.chatbot.dto;

import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

public class ChatbotResDTO {

    @Builder
    @Schema(description = "챗봇 응답")
    public record MessageResult(
            @Schema(description = "챗봇 응답 메시지", example = "제주도 2박3일이라면 성산일출봉을 추천해요.")
            String reply,

            @Schema(description = "추천 결과를 후보 블록으로 만들 수 있는 형태로 담는다. 추천이 없으면 빈 배열")
            List<Candidate> candidates
    ) {
    }

    /**
     * 챗봇이 추천한 장소·축제. 블록 생성 요청(BlockReqDTO.Create)에 그대로 대응하는 필드로 구성한다.
     *
     * <p>blockId는 담지 않는다 — 서버가 블록을 만들지 않으므로 아직 존재하지 않는 값이다.
     * 후보를 실제 블록으로 만드는 것은 사용자가 후보 목록에 담는 시점이다(BOT-04).
     */
    @Builder
    @Schema(description = "추천 후보")
    public record Candidate(
            @Schema(description = "이름", example = "성산일출봉")
            String name,

            @Schema(description = "블록 카테고리", example = "SPOT")
            BlockCategory category,

            @Schema(description = "위도", example = "33.4581")
            Double lat,

            @Schema(description = "경도", example = "126.9425")
            Double lng,

            @Schema(description = "주소", example = "제주 서귀포시 성산읍")
            String address,

            @Schema(description = "출처 시스템의 원본 ID (카카오 place id 또는 축제 contentId)", example = "12345")
            String placeId,

            @Schema(description = "출처", example = "KAKAO")
            BlockSource source,

            @Schema(description = "소분류 자유 텍스트", example = "카페")
            String subCategory,

            @Schema(description = "축제 시작일. 축제가 아니면 null", example = "2026-08-11")
            String eventStartDate,

            @Schema(description = "축제 종료일. 축제가 아니면 null", example = "2026-08-12")
            String eventEndDate,

            @Schema(description = "블록 생성 시 그대로 넘길 세부 내용. 채울 내용이 없으면 null",
                    example = "개최 기간: 2026-08-11 ~ 2026-08-12")
            String detail
    ) {
    }
}
