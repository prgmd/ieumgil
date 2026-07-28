package com.ssafy.ieumgil.domain.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class GroupReqDTO {

    /** 그룹 생성 요청 (POST /api/v0/groups) */
    public record Create(
            @Schema(description = "그룹명 (2~20자)", example = "제주 여행팀")
            @NotBlank(message = "그룹명은 필수입니다.")
            @Size(min = 2, max = 20, message = "그룹명은 2~20자여야 합니다.")
            String name
    ) {
    }

    /** 그룹명 수정 요청 (PATCH /api/v0/groups/{groupId}) */
    public record UpdateName(
            @Schema(description = "새 그룹명 (2~20자)", example = "제주 여행팀 (수정)")
            @NotBlank(message = "그룹명은 필수입니다.")
            @Size(min = 2, max = 20, message = "그룹명은 2~20자여야 합니다.")
            String name
    ) {
    }

    /**
     * 그룹 삭제 요청 (DELETE /api/v0/groups/{groupId}).
     * flat 모델이라 누구나 삭제할 수 있어, 오입력 방지용으로 그룹명을 한 번 더 받는다(MY-04).
     */
    public record Delete(
            @Schema(description = "삭제를 확인하기 위해 다시 입력한 그룹명", example = "제주 여행팀")
            @NotBlank(message = "삭제할 그룹명을 입력해주세요.")
            String confirmName
    ) {
    }
}
