package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.group.exception.GroupErrorCode;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.project.converter.ProjectConverter;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectQueryServiceImpl implements ProjectQueryService {

    private final ProjectRepository projectRepository;
    private final TravelGroupRepository travelGroupRepository;
    private final GroupMemberRepository groupMemberRepository;

    /** 그룹의 프로젝트 카드 목록 (PRJ-04) */
    @Override
    public List<ProjectResDTO.Card> getGroupProjects(Long userId, Long groupId) {
        if (travelGroupRepository.findByIdAndDeletedAtIsNull(groupId).isEmpty()) {
            throw new CustomException(GroupErrorCode.GROUP_NOT_FOUND);
        }
        if (!groupMemberRepository.existsMembership(groupId, userId)) {
            throw new CustomException(GroupErrorCode.NOT_GROUP_MEMBER);
        }

        return projectRepository.findByTravelGroupIdAndDeletedAtIsNullOrderByIdDesc(groupId)
                .stream()
                .map(ProjectConverter::toCard)
                .toList();
    }
}
