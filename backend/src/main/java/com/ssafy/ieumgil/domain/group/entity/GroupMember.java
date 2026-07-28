package com.ssafy.ieumgil.domain.group.entity;

import com.ssafy.ieumgil.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 그룹 소속(그룹 ↔ 회원 다대다 연결 테이블).
 *
 * flat 모델이라 role 컬럼이 없다 — 모든 멤버가 동등하다.
 * 탈퇴/방출은 소프트 삭제가 아니라 행 삭제이며, 블록은 author_id로 남아 자산이 유지된다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@IdClass(GroupMemberId.class)
@Table(name = "group_member")
public class GroupMember {

    /** 그룹이 하드 삭제되면 소속 행도 함께 삭제된다(ERD: ON DELETE CASCADE) */
    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TravelGroup travelGroup;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /** 가입 시점. 멤버 목록 표시 순서에 쓴다 */
    @Builder.Default
    @Column(nullable = false)
    private LocalDateTime joinedAt = LocalDateTime.now();
}
