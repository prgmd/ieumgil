package com.ssafy.ieumgil.domain.group.entity;

import com.ssafy.ieumgil.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 여행 그룹. `group`은 SQL 예약어라 테이블명을 travel_group으로 둔다.
 * 방장/owner 개념이 없는 flat 모델 — 모든 멤버가 동등하다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "travel_group")
public class TravelGroup extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String name;

    @Column(nullable = false, unique = true, length = 8)
    private String inviteCode;

    @Column(nullable = false)
    private LocalDateTime inviteExpiresAt;

    /** 소프트 삭제 시각. null이면 살아있는 그룹 */
    private LocalDateTime deletedAt;

    public void updateName(String name) {
        this.name = name;
    }

    /** 초대 코드 재발급 — 값 교체가 곧 기존 코드 무효화(GRP-06) */
    public void reissueInviteCode(String inviteCode, LocalDateTime inviteExpiresAt) {
        this.inviteCode = inviteCode;
        this.inviteExpiresAt = inviteExpiresAt;
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isInviteExpired() {
        return inviteExpiresAt.isBefore(LocalDateTime.now());
    }
}
