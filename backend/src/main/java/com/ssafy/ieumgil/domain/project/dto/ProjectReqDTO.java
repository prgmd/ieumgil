package com.ssafy.ieumgil.domain.project.dto;

import com.ssafy.ieumgil.domain.project.entity.ProjectStatus;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public class ProjectReqDTO {

    /** 프로젝트 생성 요청 (POST /api/groups/{groupId}/projects) */
    public record Create(
            @Schema(description = "프로젝트 이름", example = "제주 3박 4일")
            @NotBlank(message = "프로젝트 이름은 필수입니다.")
            @Size(max = 100, message = "프로젝트 이름은 100자 이하여야 합니다.")
            String name,

            @Schema(description = "시작일", example = "2026-08-10")
            @NotNull(message = "시작일은 필수입니다.")
            LocalDate startDate,

            @Schema(description = "종료일 (시작일 이후)", example = "2026-08-13")
            @NotNull(message = "종료일은 필수입니다.")
            LocalDate endDate,

            @Schema(description = "여행지", example = "제주")
            @Size(max = 100, message = "여행지는 100자 이하여야 합니다.")
            String destination,

            @Schema(description = "정산 인원", example = "4")
            @NotNull(message = "정산 인원은 필수입니다.")
            @Positive(message = "정산 인원은 1명 이상이어야 합니다.")
            Integer budgetHeadcount,

            @Schema(description = "목표 예산(전체 총액)", example = "300000")
            @PositiveOrZero(message = "목표 예산은 0 이상이어야 합니다.")
            Integer targetBudget,

            @Schema(description = "이동수단 선호(복수 선택)", example = "[\"CAR\"]")
            @NotEmpty(message = "이동수단은 필수입니다.")
            List<TransportPref> transportPrefs
    ) {
    }

    /** 프로젝트 이름·기간 수정 요청 (PATCH /api/projects/{projectId}). 보낸 필드만 바뀐다 */
    public record Update(
            @Schema(description = "프로젝트 이름", example = "제주 4박 5일")
            @Size(min = 1, max = 100, message = "프로젝트 이름은 1~100자여야 합니다.")
            String name,

            @Schema(description = "시작일", example = "2026-08-10")
            LocalDate startDate,

            @Schema(description = "종료일", example = "2026-08-14")
            LocalDate endDate
    ) {
    }

    /** 상태 전환 요청 (PATCH /projects/{projectId}/status). PLANNING↔DONE 양방향 */
    public record UpdateStatus(
            @Schema(description = "전환할 상태", example = "DONE")
            @NotNull(message = "status는 필수입니다.")
            ProjectStatus status
    ) {
    }

    /**
     * 정산 인원 요청 (PATCH /projects/{projectId}/budget-headcount).
     * headcount null = "그룹 멤버 수 연동으로 복귀" — 값 누락이 아니라 명시적 의도다(BGT-03).
     */
    public record UpdateHeadcount(
            @Schema(description = "정산 인원. null이면 그룹 멤버 수 연동 복귀", example = "4")
            @Positive(message = "정산 인원은 1명 이상이어야 합니다.")
            Integer headcount
    ) {
    }

    /**
     * 총 예산 변경 요청 (PATCH /projects/{projectId}/budget).
     * targetBudget null = "예산 미설정으로 초기화" — 값 누락이 아니라 명시적 의도다.
     */
    public record UpdateBudget(
            @Schema(description = "변경할 목표 예산(전체 총액). null이면 예산 미설정으로 초기화", example = "100000")
            @PositiveOrZero(message = "목표 예산은 0 이상이어야 합니다.")
            Integer targetBudget
    ) {
    }
}
