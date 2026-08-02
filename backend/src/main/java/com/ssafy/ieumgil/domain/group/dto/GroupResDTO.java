package com.ssafy.ieumgil.domain.group.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

public class GroupResDTO {

    /** 그룹 생성 응답 (POST /api/groups) */
    @Builder
    public record Created(
            Long groupId,
            String name,
            String inviteCode,
            LocalDateTime inviteExpiresAt
    ) {
    }

    /** 그룹명 수정 응답 (PATCH /api/groups/{groupId}) */
    @Builder
    public record Updated(
            Long groupId,
            String name
    ) {
    }

    /**
     * 내 그룹 목록의 한 칸 (GET /api/groups).
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

    /** 아바타 표시용 멤버 요약. 상세는 GET /api/groups/{groupId}/members 사용 */
    @Builder
    public record MemberAvatar(
            Long memberId,
            String nickname,
            String profileImg
    ) {
    }

    /** 그룹 입장 응답 (POST /api/groups/join) */
    @Builder
    public record Joined(
            Long groupId,
            String name
    ) {
    }

    /** 멤버 목록 + 초대 코드 응답 (GET /api/groups/{groupId}/members) */
    @Builder
    public record MemberList(
            String inviteCode,
            LocalDateTime inviteExpiresAt,
            List<MemberDetail> members
    ) {
    }

    /**
     * 멤버 목록의 한 명. MemberAvatar에 접속 여부가 추가된 형태.
     * online은 Redis presence 값이나 아직 미구현이라 항상 false다.
     */
    @Builder
    public record MemberDetail(
            Long memberId,
            String nickname,
            String profileImg,
            boolean online
    ) {
    }

    /** 초대 코드 재발급 응답 (POST /api/groups/{groupId}/invite-code) */
    @Builder
    public record InviteCode(
            String inviteCode,
            LocalDateTime inviteExpiresAt
    ) {
    }

    /** 그룹 나가기 응답. groupDeleted=true면 마지막 1인이 나가 그룹이 하드 삭제됨 */
    @Builder
    public record Left(
            boolean groupDeleted
    ) {
    }
}
