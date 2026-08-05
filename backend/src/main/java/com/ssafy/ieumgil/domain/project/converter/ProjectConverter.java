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
import java.util.Set;

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
                .transportPrefs(request.transportPrefs())
                .build();
    }

    public static ProjectResDTO.Created toCreated(Project project) {
        return ProjectResDTO.Created.builder()
                .projectId(project.getId())
                .build();
    }

    /** movedToPool: 기간 축소로 후보(POOL)로 밀려난 블록 id 목록 — Step 4에서 실값 연결 */
    public static ProjectResDTO.Updated toUpdated(Project project, List<Long> movedToPool) {
        return ProjectResDTO.Updated.builder()
                .projectId(project.getId())
                .name(project.getName())
                .startDate(project.getStartDate())
                .endDate(project.getEndDate())
                .movedToPool(movedToPool)
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
                .transportPrefs(project.getTransportPrefs())
                .status(project.getStatus())
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
                .transportPrefs(project.getTransportPrefs())
                .budgetHeadcount(project.getBudgetHeadcount())
                .targetBudget(project.getTargetBudget())
                .keywords(project.getKeywords() != null ? project.getKeywords() : List.of())
                .status(project.getStatus())
                .build();
    }

    /** onlineMemberIds: presence 레지스트리 기준 "지금 이 보드를 보고 있는" 멤버 (Step 5) */
    public static ProjectResDTO.Snapshot toSnapshot(
            Project project, List<Block> blocks, List<GroupMember> members,
            long lastSeq, Set<Long> onlineMemberIds) {
        return ProjectResDTO.Snapshot.builder()
                .project(toBoard(project))
                .blocks(blocks.stream().map(BlockConverter::toItem).toList())
                .members(members.stream()
                        .map(groupMember -> GroupConverter.toMemberDetail(
                                groupMember.getUser(),
                                onlineMemberIds.contains(groupMember.getUser().getId())))
                        .toList())
                .lastSeq(lastSeq)
                .build();
    }
}
