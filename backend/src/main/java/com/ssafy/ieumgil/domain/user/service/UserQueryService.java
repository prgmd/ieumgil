package com.ssafy.ieumgil.domain.user.service;

import com.ssafy.ieumgil.domain.user.dto.UserResDTO;

public interface UserQueryService {

    UserResDTO.Me getMyInfo(Long userId);
}
