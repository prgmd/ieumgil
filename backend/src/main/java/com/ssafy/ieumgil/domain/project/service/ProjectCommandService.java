package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;

public interface ProjectCommandService {

    ProjectResDTO.Created createProject(Long userId, Long groupId, ProjectReqDTO.Create request);

    ProjectResDTO.Updated updateProject(Long userId, Long projectId, String clientId, ProjectReqDTO.Update request);

    void softDeleteProject(Long userId, Long projectId, String clientId);

    ProjectResDTO.StatusChanged changeStatus(Long userId, Long projectId, String clientId, ProjectReqDTO.UpdateStatus request);

    ProjectResDTO.HeadcountChanged changeBudgetHeadcount(Long userId, Long projectId, String clientId, ProjectReqDTO.UpdateHeadcount request);
}
