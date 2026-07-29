package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.project.converter.ProjectConverter;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectQueryServiceImpl implements ProjectQueryService {

    private final ProjectRepository projectRepository;

    /** 그룹의 프로젝트 카드 목록 (PRJ-04) */
    @Override
    public List<ProjectResDTO.Card> getGroupProjects(Long userId, Long groupId) {
        return projectRepository.findByTravelGroupIdAndDeletedAtIsNullOrderByIdDesc(groupId)
                .stream()
                .map(ProjectConverter::toCard)
                .toList();
    }
}
