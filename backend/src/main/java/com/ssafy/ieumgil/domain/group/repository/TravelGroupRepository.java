package com.ssafy.ieumgil.domain.group.repository;

import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TravelGroupRepository extends JpaRepository<TravelGroup, Long> {

    /** 초대 코드 충돌 검사용. 생성 시 중복이면 다시 뽑는다. */
    boolean existsByInviteCode(String inviteCode);

    /** 소프트 삭제되지 않은 그룹만 조회. 삭제된 그룹은 없는 것으로 취급한다. */
    Optional<TravelGroup> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 내가 소속된 그룹 목록. GroupMember를 거쳐 그룹을 찾으므로 JPQL로 직접 작성한다.
     * 삭제된 그룹은 제외하고, 최근 만든 그룹이 위로 오도록 정렬한다.
     */
    @Query("""
            SELECT gm.travelGroup
            FROM GroupMember gm
            WHERE gm.user.id = :userId
              AND gm.travelGroup.deletedAt IS NULL
            ORDER BY gm.travelGroup.id DESC
            """)
    List<TravelGroup> findMyGroups(@Param("userId") Long userId);
}
