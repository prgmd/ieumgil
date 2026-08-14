package com.ssafy.ieumgil.domain.group.repository;

import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.group.exception.GroupErrorCode;
import com.ssafy.ieumgil.global.exception.CustomException;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TravelGroupRepository extends JpaRepository<TravelGroup, Long> {

    /** 초대 코드 충돌 검사용. 생성 시 중복이면 다시 뽑는다. */
    boolean existsByInviteCode(String inviteCode);

    /** 소프트 삭제되지 않은 그룹만 조회. 삭제된 그룹은 없는 것으로 취급한다. */
    Optional<TravelGroup> findByIdAndDeletedAtIsNull(Long id);

    /** 살아 있는 그룹을 가져오거나 404(GROUP_NOT_FOUND)를 던진다. 서비스에 복붙되던 관용구를 한곳으로 모은다. */
    default TravelGroup findAliveByIdOrThrow(Long id) {
        return findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new CustomException(GroupErrorCode.GROUP_NOT_FOUND));
    }

    /** GroupMemberAspect의 존재 확인용. 엔티티가 필요 없으니 exists로 가볍게 */
    boolean existsByIdAndDeletedAtIsNull(Long id);

    /**
     * 탈퇴 처리용 조회 — 행 잠금(SELECT ... FOR UPDATE).
     *
     * leaveGroup의 "마지막 1인이면 하드 삭제" 판정은 count 조회 → 삭제의 check-then-act라,
     * 2인 그룹에서 동시 탈퇴하면 둘 다 count=2를 읽어 아무도 그룹을 지우지 않는다 —
     * 멤버 0명인 좀비 그룹이 영구 잔존한다(READ COMMITTED에선 삭제 후 재확인도
     * 서로의 미커밋 삭제를 못 봐서 같은 결말). 그룹 행을 잠가 같은 그룹의 탈퇴를
     * 직렬화하면 두 번째 탈퇴자가 반드시 count=1을 보고 그룹을 지운다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM TravelGroup g WHERE g.id = :groupId AND g.deletedAt IS NULL")
    Optional<TravelGroup> findByIdForUpdate(@Param("groupId") Long groupId);

    /** 잠금 조회 + 404 관용구 — findAliveByIdOrThrow의 잠금 버전 */
    default TravelGroup findAliveByIdForUpdateOrThrow(Long id) {
        return findByIdForUpdate(id)
                .orElseThrow(() -> new CustomException(GroupErrorCode.GROUP_NOT_FOUND));
    }

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
