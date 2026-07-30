package com.ssafy.ieumgil.domain.group.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * GroupMember의 복합 PK (group_id, member_id).
 *
 * 규칙:
 * - 필드명이 GroupMember의 @Id 필드명과 정확히 같아야 한다 (travelGroup, user)
 * - 타입은 상대 엔티티의 PK 타입 (둘 다 Long)
 * - Serializable 구현 + equals/hashCode 필수 — JPA가 이 값으로 엔티티를 식별한다
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class GroupMemberId implements Serializable {

    private Long travelGroup;

    private Long user;
}
