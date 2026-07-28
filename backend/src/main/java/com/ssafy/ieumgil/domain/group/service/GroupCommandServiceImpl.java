package com.ssafy.ieumgil.domain.group.service;

import com.ssafy.ieumgil.domain.group.converter.GroupConverter;
import com.ssafy.ieumgil.domain.group.dto.GroupReqDTO;
import com.ssafy.ieumgil.domain.group.dto.GroupResDTO;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.group.exception.GroupErrorCode;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.group.util.InviteCodeGenerator;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.domain.user.exception.UserErrorCode;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class GroupCommandServiceImpl implements GroupCommandService {

    /** 초대 코드 유효 기간 (ERD: 발급 +7일) */
    private static final int INVITE_CODE_VALID_DAYS = 7;

    /** 초대 코드 중복 시 재시도 횟수. 32^8 ≈ 1.1조라 실제로 겹칠 일은 거의 없다 */
    private static final int INVITE_CODE_MAX_ATTEMPTS = 5;

    private final TravelGroupRepository travelGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;

    /**
     * 그룹 생성 (GRP-01). 그룹 저장과 생성자를 첫 멤버로 등록하는 작업이
     * 한 트랜잭션이라, 둘 중 하나만 성공하는 상태가 생기지 않는다.
     */
    @Override
    public GroupResDTO.Created createGroup(Long userId, GroupReqDTO.Create request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        TravelGroup group = travelGroupRepository.save(GroupConverter.toTravelGroup(
                request.name(),
                issueUniqueInviteCode(),
                LocalDateTime.now().plusDays(INVITE_CODE_VALID_DAYS)));

        groupMemberRepository.save(GroupConverter.toGroupMember(group, user));

        return GroupConverter.toCreated(group);
    }

    /** 그룹명 수정 (GRP-05). flat 모델이라 멤버 누구나 가능 */
    @Override
    public GroupResDTO.Updated updateGroupName(Long userId, Long groupId, GroupReqDTO.UpdateName request) {
        TravelGroup group = getGroupAsMember(userId, groupId);

        group.updateName(request.name());   // 변경 감지로 트랜잭션 종료 시 UPDATE

        return GroupConverter.toUpdated(group);
    }

    /**
     * 그룹 소프트 삭제 (MY-04). flat 모델이라 멤버 누구나 가능해서
     * 오조작 방지용으로 그룹명 재입력을 검증한다. 스케줄러가 30일 경과분을 하드 삭제.
     */
    @Override
    public void softDeleteGroup(Long userId, Long groupId, GroupReqDTO.Delete request) {
        TravelGroup group = getGroupAsMember(userId, groupId);

        if (!group.getName().equals(request.confirmName())) {
            throw new CustomException(GroupErrorCode.GROUP_NAME_MISMATCH);
        }

        group.softDelete();
    }

    /**
     * 그룹 조회 + 요청자의 멤버십 검증.
     * 6단계에서 @GroupMember AOP로 옮길 예정이라 지금은 서비스에서 직접 검사한다.
     */
    private TravelGroup getGroupAsMember(Long userId, Long groupId) {
        TravelGroup group = travelGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new CustomException(GroupErrorCode.GROUP_NOT_FOUND));

        if (!groupMemberRepository.existsMembership(groupId, userId)) {
            throw new CustomException(GroupErrorCode.NOT_GROUP_MEMBER);
        }

        return group;
    }

    /** 중복되지 않는 초대 코드를 뽑는다. 재시도 한도를 넘으면 실패로 처리한다 */
    private String issueUniqueInviteCode() {
        for (int attempt = 0; attempt < INVITE_CODE_MAX_ATTEMPTS; attempt++) {
            String code = inviteCodeGenerator.generate();

            if (!travelGroupRepository.existsByInviteCode(code)) {
                return code;
            }
        }

        throw new CustomException(GroupErrorCode.INVITE_CODE_ISSUE_FAILED);
    }
}
