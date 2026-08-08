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

    /** GroupMemberAspect의 존재 확인용. 엔티티가 필요 없으니 exists로 가볍게 */
    boolean existsByIdAndDeletedAtIsNull(Long id);

    /**
     * 초대 코드로 그룹 조회 (입장용).
     * 만료 여부는 여기서 걸러내지 않는다 — 만료된 코드는 404가 아니라 410으로 알려줘야 하므로,
     * 일단 찾아온 뒤 서비스에서 isInviteExpired()로 판단한다.
     */
    Optional<TravelGroup> findByInviteCodeAndDeletedAtIsNull(String inviteCode);

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
