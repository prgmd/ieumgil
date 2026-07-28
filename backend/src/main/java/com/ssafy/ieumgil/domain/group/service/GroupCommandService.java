package com.ssafy.ieumgil.domain.group.service;

import com.ssafy.ieumgil.domain.group.dto.GroupReqDTO;
import com.ssafy.ieumgil.domain.group.dto.GroupResDTO;

public interface GroupCommandService {

    GroupResDTO.Created createGroup(Long userId, GroupReqDTO.Create request);

    GroupResDTO.Updated updateGroupName(Long userId, Long groupId, GroupReqDTO.UpdateName request);

    void softDeleteGroup(Long userId, Long groupId, GroupReqDTO.Delete request);
}
