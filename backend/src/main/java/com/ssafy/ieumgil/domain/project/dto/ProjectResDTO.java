package com.ssafy.ieumgil.domain.project.dto;

import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.group.dto.GroupResDTO;
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

    /** 대시보드 스냅샷의 프로젝트 상세 파트 */
    @Builder
    public record Board(
            Long projectId,
            String name,
            String destination,
            LocalDate startDate,
            LocalDate endDate,
            TransportPref transportPref,
            Integer budgetHeadcount,
            Integer targetBudget,
            List<String> keywords,
            ProjectStatus status,
            String themeColor
    ) {
    }

    /**
     * 대시보드 스냅샷 (GET /api/projects/{projectId}).
     * 최초 로딩과 재연결 복구가 같은 응답을 쓴다 — 클라이언트는 lastSeq를 기준으로
     * 이후 op(ops?afterSeq=)를 이어받아 상태를 맞춘다.
     * members[].online은 presence(Step 5) 연동 전까지 false 고정.
     */
    @Builder
    public record Snapshot(
            Board project,
            List<BlockResDTO.Item> blocks,
            List<GroupResDTO.MemberDetail> members,
            long lastSeq
    ) {
    }
}
