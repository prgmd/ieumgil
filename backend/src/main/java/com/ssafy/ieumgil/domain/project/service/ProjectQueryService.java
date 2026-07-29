package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;

import java.util.List;

public interface ProjectQueryService {

    List<ProjectResDTO.Card> getGroupProjects(Long userId, Long groupId);
}
