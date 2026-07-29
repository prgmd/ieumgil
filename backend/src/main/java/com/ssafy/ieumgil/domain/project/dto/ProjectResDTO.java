package com.ssafy.ieumgil.domain.project.dto;

import com.ssafy.ieumgil.domain.project.entity.ProjectStatus;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import lombok.Builder;

import java.time.LocalDate;
import java.util.List;

public class ProjectResDTO {

    /** 프로젝트 생성 응답 */
    @Builder
    public record Created(
            Long projectId
    ) {
    }

    /**
     * 프로젝트 수정 응답.
     * movedToPool은 기간 축소로 후보로 밀려난 블록 id 목록. 블록 기능 구현 전까지 항상 빈 배열이다.
     */
    @Builder
    public record Updated(
            Long projectId,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            List<Long> movedToPool
    ) {
    }

    /** 그룹 페이지의 프로젝트 카드 */
    @Builder
    public record Card(
            Long projectId,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            String destination,
            Integer budgetHeadcount,
            TransportPref transportPref,
            ProjectStatus status,
            String themeColor
    ) {
    }
}
