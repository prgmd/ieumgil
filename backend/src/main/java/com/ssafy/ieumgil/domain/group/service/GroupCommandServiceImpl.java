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

    /** 그룹 정원 (ERD: 최대 10명) */
    private static final int MAX_GROUP_MEMBERS = 10;

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

    /**
     * 초대 코드로 그룹 입장 (GRP-03).
     * 검증 순서가 곧 응답 코드 순서다 — 404(없는 코드) → 410(만료) → 409(이미 멤버) → 409(정원).
     *
     * 정원 검증에 비관적 락을 걸지 않아, 9명일 때 두 명이 동시에 들어오면 11명이 될 수 있다.
     * 실질적 피해가 없고 일정을 우선해 감수하기로 결정했다(2026-07-29). 필요해지면 리포지토리 메서드에
     * @Lock(PESSIMISTIC_WRITE)만 추가하면 되고 이 코드는 그대로 둔다.
     */
    @Override
    public GroupResDTO.Joined joinGroup(Long userId, GroupReqDTO.Join request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));

        TravelGroup group = travelGroupRepository.findByInviteCodeAndDeletedAtIsNull(request.inviteCode())
                .orElseThrow(() -> new CustomException(GroupErrorCode.INVITE_CODE_NOT_FOUND));

        if (group.isInviteExpired()) {
            throw new CustomException(GroupErrorCode.INVITE_CODE_EXPIRED);
        }
        if (groupMemberRepository.existsMembership(group.getId(), userId)) {
            throw new CustomException(GroupErrorCode.ALREADY_GROUP_MEMBER);
        }
        if (groupMemberRepository.countMembers(group.getId()) >= MAX_GROUP_MEMBERS) {
            throw new CustomException(GroupErrorCode.GROUP_FULL);
        }

        groupMemberRepository.save(GroupConverter.toGroupMember(group, user));

        return GroupConverter.toJoined(group);
    }

    /** 초대 코드 재발급 (GRP-06). 값 교체가 곧 기존 코드 무효화 */
    @Override
    public GroupResDTO.InviteCode reissueInviteCode(Long userId, Long groupId) {
        TravelGroup group = getGroup(groupId);

        group.reissueInviteCode(
                issueUniqueInviteCode(),
                LocalDateTime.now().plusDays(INVITE_CODE_VALID_DAYS));

        return GroupConverter.toInviteCode(group);
    }

    /**
     * 자발적 탈퇴 (GRP-09). 마지막 1인이 나가면 그룹을 하드 삭제한다 —
     * 복구할 멤버가 없으므로 소프트 삭제로 남겨둘 이유가 없다.
     * 프로젝트는 FK의 ON DELETE CASCADE로 함께 지워진다.
     */
    @Override
    public GroupResDTO.Left leaveGroup(Long userId, Long groupId) {
        boolean lastMember = groupMemberRepository.countMembers(groupId) <= 1;

        groupMemberRepository.deleteMembership(groupId, userId);

        if (lastMember) {
            travelGroupRepository.deleteById(groupId);
        }

        return GroupConverter.toLeft(lastMember);
    }

    /** 그룹명 수정 (GRP-05). flat 모델이라 멤버 누구나 가능 */
    @Override
    public GroupResDTO.Updated updateGroupName(Long userId, Long groupId, GroupReqDTO.UpdateName request) {
        TravelGroup group = getGroup(groupId);

        group.updateName(request.name());   // 변경 감지로 트랜잭션 종료 시 UPDATE

        return GroupConverter.toUpdated(group);
    }

    /**
     * 그룹 소프트 삭제 (MY-04). flat 모델이라 멤버 누구나 가능해서
     * 오조작 방지용으로 그룹명 재입력을 검증한다. 스케줄러가 30일 경과분을 하드 삭제.
     */
    @Override
    public void softDeleteGroup(Long userId, Long groupId, GroupReqDTO.Delete request) {
        TravelGroup group = getGroup(groupId);

        if (!group.getName().equals(request.confirmName())) {
            throw new CustomException(GroupErrorCode.GROUP_NAME_MISMATCH);
        }

        group.softDelete();
    }

    /**
     * 그룹 조회. 존재 확인(404)·멤버십(403)은 컨트롤러의 @GroupMember가 이미 끝냈으므로
     * 여기서는 엔티티만 가져온다. orElseThrow는 그 사이 삭제된 극단적 경우의 방어선이다.
     */
    private TravelGroup getGroup(Long groupId) {
        return travelGroupRepository.findByIdAndDeletedAtIsNull(groupId)
                .orElseThrow(() -> new CustomException(GroupErrorCode.GROUP_NOT_FOUND));
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
