package com.ssafy.ieumgil.domain.group.repository;

import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.GroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    /**
     * 요청자가 해당 그룹의 멤버인지 검증. 비멤버면 403으로 막는다.
     *
     * 이름 규칙(existsByTravelGroupIdAndUserId)으로도 되지만, @Id가 연관관계 필드라
     * Spring Data가 travel_group·users를 조인해 전 컬럼을 읽는 쿼리를 만든다.
     * gm.travelGroup.id는 FK 컬럼 자체라 JPQL로 쓰면 조인 없이 count만 나간다.
     */
    @Query("""
            SELECT COUNT(gm) > 0
            FROM GroupMember gm
            WHERE gm.travelGroup.id = :groupId
              AND gm.user.id = :userId
            """)
    boolean existsMembership(@Param("groupId") Long groupId, @Param("userId") Long userId);

    /**
     * 여러 그룹의 멤버를 한 번에 조회한다. 아바타 표시에 nickname·profileImg가 필요하므로
     * JOIN FETCH로 User까지 함께 가져와 N+1을 막는다.
     */
    @Query("""
            SELECT gm
            FROM GroupMember gm
            JOIN FETCH gm.user
            WHERE gm.travelGroup.id IN :groupIds
            ORDER BY gm.joinedAt ASC
            """)
    List<GroupMember> findAllWithUserByGroupIdIn(@Param("groupIds") List<Long> groupIds);
}
