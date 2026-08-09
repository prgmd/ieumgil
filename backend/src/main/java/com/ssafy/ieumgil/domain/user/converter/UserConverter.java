package com.ssafy.ieumgil.domain.user.converter;

import com.ssafy.ieumgil.domain.user.dto.UserResDTO;
import com.ssafy.ieumgil.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserConverter {

    public static UserResDTO.Me toMe(User user) {
        return UserResDTO.Me.builder()
                .id(user.getId())
                .nickname(user.getNickname())
                .profileImg(user.getProfileImageUrl())   // 엔티티 profileImageUrl → 응답 profileImg
                .build();
    }
}
