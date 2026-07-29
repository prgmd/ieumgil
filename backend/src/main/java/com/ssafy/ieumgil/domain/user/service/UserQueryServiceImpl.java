package com.ssafy.ieumgil.domain.user.service;

import com.ssafy.ieumgil.domain.user.converter.UserConverter;
import com.ssafy.ieumgil.domain.user.dto.UserResDTO;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.domain.user.exception.UserErrorCode;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryServiceImpl implements UserQueryService {

    private final UserRepository userRepository;

    @Override
    public UserResDTO.Me getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        return UserConverter.toMe(user);
    }
}
