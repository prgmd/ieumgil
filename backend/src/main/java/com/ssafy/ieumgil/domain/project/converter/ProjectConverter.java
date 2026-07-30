package com.ssafy.ieumgil.domain.project.converter;

import com.ssafy.ieumgil.domain.block.converter.BlockConverter;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.group.converter.GroupConverter;
import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ProjectConverter {

    /** status는 엔티티의 @Builder.Default가 PLANNING으로 채운다 */
    public static Project toProject(TravelGroup travelGroup, ProjectReqDTO.Create request) {
        return Project.builder()
                .travelGroup(travelGroup)
                .name(request.name())
                .startDate(request.startDate())
                .endDate(request.endDate())
                .destination(request.destination())
                .budgetHeadcount(request.budgetHeadcount())
                .targetBudget(request.targetBudget())
                .transportPref(request.transportPref())
                .build();
    }

    public static ProjectResDTO.Created toCreated(Project project) {
        return ProjectResDTO.Created.builder()
                .projectId(project.getId())
                .build();
    }

    /** movedToPool은 블록 기능 구현 전까지 빈 배열 */
    public static ProjectResDTO.Updated toUpdated(Project project) {
        return ProjectResDTO.Updated.builder()
                .projectId(project.getId())
                .name(project.getName())
                .startDate(project.getStartDate())
                .endDate(project.getEndDate())
                .movedToPool(List.of())
                .build();
    }

    public static ProjectResDTO.Card toCard(Project project) {
        return ProjectResDTO.Card.builder()
                .projectId(project.getId())
                .name(project.getName())
                .startDate(project.getStartDate())
                .endDate(project.getEndDate())
                .destination(project.getDestination())
                .budgetHeadcount(project.getBudgetHeadcount())
                .transportPref(project.getTransportPref())
                .status(project.getStatus())
                .themeColor(project.getThemeColor())
                .build();
    }

    /** keywords는 챗봇이 아직 안 채운 프로젝트에서 null일 수 있다 — 프론트가 배열을 전제하므로 빈 배열로 */
    public static ProjectResDTO.Board toBoard(Project project) {
        return ProjectResDTO.Board.builder()
                .projectId(project.getId())
                .name(project.getName())
                .destination(project.getDestination())
                .startDate(project.getStartDate())
                .endDate(project.getEndDate())
                .transportPref(project.getTransportPref())
                .budgetHeadcount(project.getBudgetHeadcount())
                .targetBudget(project.getTargetBudget())
                .keywords(project.getKeywords() != null ? project.getKeywords() : List.of())
                .status(project.getStatus())
                .themeColor(project.getThemeColor())
                .build();
    }

    public static ProjectResDTO.Snapshot toSnapshot(
            Project project, List<Block> blocks, List<GroupMember> members, long lastSeq) {
        return ProjectResDTO.Snapshot.builder()
                .project(toBoard(project))
                .blocks(blocks.stream().map(BlockConverter::toItem).toList())
                .members(members.stream()
                        .map(groupMember -> GroupConverter.toMemberDetail(groupMember.getUser()))
                        .toList())
                .lastSeq(lastSeq)
                .build();
    }
}
