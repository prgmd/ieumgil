package com.ssafy.ieumgil.domain.project.converter;

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
}
