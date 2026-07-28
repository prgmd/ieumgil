package com.ssafy.ieumgil.domain.auth.service;

import com.ssafy.ieumgil.domain.auth.dto.AuthResDTO;

public interface AuthCommandService {

    AuthResDTO.LoginResult kakaoLogin(String code);

    AuthResDTO.ReissueResult reissue(String refreshToken);

    void logout(Long userId);
}

