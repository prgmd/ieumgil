package com.ssafy.ieumgil.domain.group.service;

import com.ssafy.ieumgil.domain.group.dto.GroupResDTO;

import java.util.List;

public interface GroupQueryService {

    List<GroupResDTO.Summary> getMyGroups(Long userId);

    GroupResDTO.MemberList getMembers(Long userId, Long groupId);
}
