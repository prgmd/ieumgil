package com.ssafy.ieumgil.domain.group.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

public class GroupResDTO {

    /** 그룹 생성 응답 (POST /api/v0/groups) */
    @Builder
    public record Created(
            Long groupId,
            String name,
            String inviteCode,
            LocalDateTime inviteExpiresAt
    ) {
    }

    /** 그룹명 수정 응답 (PATCH /api/v0/groups/{groupId}) */
    @Builder
    public record Updated(
            Long groupId,
            String name
    ) {
    }

    /**
     * 내 그룹 목록의 한 칸 (GET /api/v0/groups).
     * tripCount는 완료 여부와 무관한 전체 프로젝트 수.
     */
    @Builder
    public record Summary(
            Long groupId,
            String name,
            int memberCount,
            long tripCount,
            List<MemberAvatar> members
    ) {
    }

    /** 아바타 표시용 멤버 요약. 상세는 GET /api/v0/groups/{groupId}/members 사용 */
    @Builder
    public record MemberAvatar(
            Long memberId,
            String nickname,
            String profileImg
    ) {
    }
}
