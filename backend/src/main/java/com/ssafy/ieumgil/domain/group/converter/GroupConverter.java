package com.ssafy.ieumgil.domain.group.converter;

import com.ssafy.ieumgil.domain.group.dto.GroupResDTO;
import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GroupConverter {

    // ---------- 요청 → 엔티티 ----------

    public static TravelGroup toTravelGroup(String name, String inviteCode, LocalDateTime inviteExpiresAt) {
        return TravelGroup.builder()
                .name(name)
                .inviteCode(inviteCode)
                .inviteExpiresAt(inviteExpiresAt)
                .build();
    }

    /** joinedAt은 엔티티의 @Builder.Default가 현재 시각으로 채운다 */
    public static GroupMember toGroupMember(TravelGroup travelGroup, User user) {
        return GroupMember.builder()
                .travelGroup(travelGroup)
                .user(user)
                .build();
    }

    // ---------- 엔티티 → 응답 ----------

    public static GroupResDTO.Created toCreated(TravelGroup group) {
        return GroupResDTO.Created.builder()
                .groupId(group.getId())
                .name(group.getName())
                .inviteCode(group.getInviteCode())
                .inviteExpiresAt(group.getInviteExpiresAt())
                .build();
    }

    public static GroupResDTO.Updated toUpdated(TravelGroup group) {
        return GroupResDTO.Updated.builder()
                .groupId(group.getId())
                .name(group.getName())
                .build();
    }

    public static GroupResDTO.MemberAvatar toMemberAvatar(User user) {
        return GroupResDTO.MemberAvatar.builder()
                .memberId(user.getId())
                .nickname(user.getNickname())
                .profileImg(user.getProfileImageUrl())
                .build();
    }

    /** memberCount는 넘겨받은 멤버 목록 크기로 계산해 별도 count 쿼리를 쓰지 않는다 */
    public static GroupResDTO.Summary toSummary(TravelGroup group, List<GroupMember> members, long tripCount) {
        return GroupResDTO.Summary.builder()
                .groupId(group.getId())
                .name(group.getName())
                .memberCount(members.size())
                .tripCount(tripCount)
                .members(members.stream()
                        .map(groupMember -> toMemberAvatar(groupMember.getUser()))
                        .toList())
                .build();
    }
}
