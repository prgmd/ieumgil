package com.ssafy.ieumgil.domain.group.repository;

import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.GroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GroupMemberRepository extends JpaRepository<GroupMember, GroupMemberId> {

    /** 요청자가 해당 그룹의 멤버인지 검증. 비멤버면 403으로 막는다. */
    boolean existsByTravelGroupIdAndUserId(Long travelGroupId, Long userId);

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
