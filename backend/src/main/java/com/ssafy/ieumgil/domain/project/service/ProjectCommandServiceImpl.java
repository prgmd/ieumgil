package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.group.exception.GroupErrorCode;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.project.converter.ProjectConverter;
import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectCommandServiceImpl implements ProjectCommandService {

    private final ProjectRepository projectRepository;
    private final TravelGroupRepository travelGroupRepository;
    private final GroupMemberRepository groupMemberRepository;

    /** 프로젝트 생성 (PRJ-01) */
    @Override
    public ProjectResDTO.Created createProject(Long userId, Long groupId, ProjectReqDTO.Create request) {
        TravelGroup group = getGroupAsMember(userId, groupId);

        validateDateRange(request.startDate(), request.endDate());

        Project project = projectRepository.save(ProjectConverter.toProject(group, request));

        return ProjectConverter.toCreated(project);
    }

    /** 프로젝트 이름·기간 수정 (PRJ-02). null인 필드는 건드리지 않는다 */
    @Override
    public ProjectResDTO.Updated updateProject(Long userId, Long projectId, ProjectReqDTO.Update request) {
        Project project = getProjectAsMember(userId, projectId);

        // 부분 수정이라 안 보낸 필드는 기존 값과 비교해야 한다
        LocalDate startDate = request.startDate() != null ? request.startDate() : project.getStartDate();
        LocalDate endDate = request.endDate() != null ? request.endDate() : project.getEndDate();
        validateDateRange(startDate, endDate);

        project.updateInfo(request.name(), request.startDate(), request.endDate());

        return ProjectConverter.toUpdated(project);
    }

    /** 프로젝트 소프트 삭제 (PRJ-03). 그룹 복구 시 함께 살아나야 하므로 행을 남긴다 */
    @Override
    public void softDeleteProject(Long userId, Long projectId) {
        Project project = getProjectAsMember(userId, projectId);

        project.softDelete();
    }

    // ----- 공통 -----

    /** 6단계에서 @GroupMember AOP로 옮길 예정 */
    private TravelGroup getGroupAsMember(Long userId, Long groupId) {
        TravelGroup group = travelGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new CustomException(GroupErrorCode.GROUP_NOT_FOUND));

        if (!groupMemberRepository.existsMembership(groupId, userId)) {
            throw new CustomException(GroupErrorCode.NOT_GROUP_MEMBER);
        }

        return group;
    }

    /** 프로젝트가 속한 그룹의 멤버인지 검증한다 */
    private Project getProjectAsMember(Long userId, Long projectId) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new CustomException(ProjectErrorCode.PROJECT_NOT_FOUND));

        if (!groupMemberRepository.existsMembership(project.getTravelGroup().getId(), userId)) {
            throw new CustomException(GroupErrorCode.NOT_GROUP_MEMBER);
        }

        return project;
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new CustomException(ProjectErrorCode.INVALID_DATE_RANGE);
        }
    }
}
