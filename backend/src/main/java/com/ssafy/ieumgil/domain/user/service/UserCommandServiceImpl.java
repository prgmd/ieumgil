package com.ssafy.ieumgil.domain.user.service;

import com.ssafy.ieumgil.domain.auth.repository.RefreshTokenRepository;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.domain.user.exception.UserErrorCode;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.global.websocket.WsSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class UserCommandServiceImpl implements UserCommandService {

    private final UserRepository userRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final TravelGroupRepository travelGroupRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final WsSessionRegistry wsSessionRegistry;

    /**
     * 회원 탈퇴 (AUTH-05).
     *
     * users 행은 지우지 않는다 — 블록의 author_id가 참조하므로 하드 삭제하면 작성자 표기가 깨진다.
     * 대신 개인정보만 파기하고(kakaoId null, 닉네임 교체, 프로필 null) deletedAt을 남긴다.
     * kakaoId가 null이 되므로 같은 카카오 계정으로 재로그인하면 새 회원으로 가입된다.
     *
     * refresh token을 지우는 것이 실질적인 접근 차단이다. 이미 발급된 access token은
     * 최대 30분간 유효하지만, 소속 그룹이 전부 정리되어 그룹 API는 403이 된다.
     * REST가 막혀도 열려 있는 WS는 그대로이므로 세션까지 끊어야 실제로 차단된다(GRP-09).
     */
    @Override
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        if (user.isWithdrawn()) {
            throw new CustomException(UserErrorCode.USER_NOT_FOUND);
        }

        // ⚠️ 순서 주의 — withdraw()가 먼저다.
        // leaveAllGroups()의 @Modifying(clearAutomatically = true)가 영속성 컨텍스트를 비우므로,
        // 그 뒤에 withdraw()를 호출하면 user가 detached 상태라 변경 감지가 동작하지 않아 UPDATE가 누락된다.
        // flushAutomatically = true 덕분에 DELETE 직전에 이 UPDATE가 먼저 DB로 나간다.
        user.withdraw();

        leaveAllGroups(userId);

        refreshTokenRepository.deleteByUserId(userId);

        wsSessionRegistry.disconnect(userId);
    }

    /**
     * 소속 그룹 전부에서 나간다. 나간 뒤 아무도 남지 않은 그룹은 하드 삭제한다 —
     * 자발적 탈퇴(GRP-09)의 "마지막 1인" 규칙과 동일하다.
     */
    private void leaveAllGroups(Long userId) {
        List<Long> groupIds = groupMemberRepository.findGroupIdsByUserId(userId);

        if (groupIds.isEmpty()) {
            return;
        }

        groupMemberRepository.deleteAllMembershipsOfUser(userId);

        groupIds.stream()
                .filter(groupId -> groupMemberRepository.countMembers(groupId) == 0)
                .forEach(travelGroupRepository::deleteById);
    }
}
