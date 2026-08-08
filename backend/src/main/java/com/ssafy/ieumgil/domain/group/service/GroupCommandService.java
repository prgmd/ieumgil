package com.ssafy.ieumgil.domain.group.service;

import com.ssafy.ieumgil.domain.group.dto.GroupReqDTO;
import com.ssafy.ieumgil.domain.group.dto.GroupResDTO;

public interface GroupCommandService {

    GroupResDTO.Created createGroup(Long userId, GroupReqDTO.Create request);

    GroupResDTO.Joined joinGroup(Long userId, GroupReqDTO.Join request);

    GroupResDTO.InviteCode reissueInviteCode(Long userId, Long groupId);

    GroupResDTO.Left leaveGroup(Long userId, Long groupId);

    GroupResDTO.Updated updateGroupName(Long userId, Long groupId, GroupReqDTO.UpdateName request);
}
