package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;

public interface ProjectCommandService {

    ProjectResDTO.Created createProject(Long userId, Long groupId, ProjectReqDTO.Create request);

    ProjectResDTO.Updated updateProject(Long userId, Long projectId, ProjectReqDTO.Update request);

    void softDeleteProject(Long userId, Long projectId);
}
