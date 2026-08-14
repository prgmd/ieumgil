package com.ssafy.ieumgil.domain.block.repository;

import com.ssafy.ieumgil.domain.block.entity.Block;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BlockRepository extends JpaRepository<Block, Long> {

    /**
     * id 목록으로 살아있는 블록을 조회한다. 프로젝트 조건을 함께 걸어 소유권까지 확인한다.
     *
     * <p>교통 후보 계산이 이걸 쓴다 — 남의 프로젝트 블록 id를 섞어 보내 좌표를 알아내는 것을
     * 막아야 하는데, 컨트롤러 인가는 "이 프로젝트 멤버인가"까지만 본다.
     */
    List<Block> findAllByIdInAndProject_IdAndDeletedAtIsNull(Collection<Long> ids, Long projectId);

    /**
     * 프로젝트의 살아있는 블록 전체 — 스냅샷용.
     * 정렬은 (startOffsetMinutes, orderKey, id) — 보드 순서는 절대 오프셋이 정하고,
     * 오프셋이 같을 때만 orderKey가 가른다(동시 삽입으로 orderKey까지 같아질 수 있어 id로 최종 tie-break).
     * 오프셋이 null인 후보(POOL)는 NULLS LAST로 맨 뒤에 모인다.
     * 이 쿼리의 (project_id, deleted_at IS NULL) 조건이 partial index ix_block_chain의 존재 이유다.
     */
    @Query("""
            SELECT b
            FROM Block b
            WHERE b.project.id = :projectId
              AND b.deletedAt IS NULL
            ORDER BY b.startOffsetMinutes ASC NULLS LAST, b.orderKey ASC, b.id ASC
            """)
    List<Block> findChain(@Param("projectId") Long projectId);

    /**
     * GroupMemberAspect(BLOCK_ID)의 그룹 역추적용 — blockId → projectId → groupId를 한 쿼리로.
     * tombstone 블록도 포함한다: 삭제된 블록에 온 지연 op는 404가 아니라 410이어야 하므로
     * 인가(AOP)는 통과시키고 서비스가 410을 판정한다.
     * 단, 프로젝트가 소프트 삭제됐으면 groupId를 돌려주지 않는다 — 삭제된 프로젝트의
     * 블록까지 인가가 통과하면 안 되므로 프로젝트 생존은 여기서 걸러 인가를 실패시킨다.
     */
    @Query("SELECT b.project.travelGroup.id FROM Block b WHERE b.id = :blockId AND b.project.deletedAt IS NULL")
    Optional<Long> findGroupIdById(@Param("blockId") Long blockId);

    /** detail-lock 배지 전파 대상 토픽을 찾기 위한 projectId 조회 — FK 값만, 조인 없음 */
    @Query("SELECT b.project.id FROM Block b WHERE b.id = :blockId")
    Optional<Long> findProjectIdById(@Param("blockId") Long blockId);

    /**
     * 블록 변경용 조회 — 행 잠금(SELECT ... FOR UPDATE).
     *
     * 같은 블록을 두 트랜잭션이 동시에 고치면 Hibernate가 전체 컬럼 UPDATE를 쓰는 탓에
     * 마지막 커밋이 앞선 커밋의 다른 필드까지 행 단위로 덮는다 — 동시 5필드 수정에서
     * 4필드가 유실되는 것을 실측으로 확인했다. 같은 블록의 서버 측 쓰기만
     * 직렬화하고 다른 블록끼리는 병렬 그대로다. 클라이언트 간 스테일 판정은 여전히 LWW 몫.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Block b WHERE b.id = :blockId")
    Optional<Block> findByIdForUpdate(@Param("blockId") Long blockId);

    /**
     * 체인 말단 키 — 생성 시 orderKey 미지정 폴백.
     * Day 구분이 없다: 보드 순서는 이제 오프셋이 정하고 orderKey는 tie-break·POOL 정렬만 맡는다.
     */
    Optional<Block> findTopByProject_IdAndDeletedAtIsNullOrderByOrderKeyDescIdDesc(Long projectId);

    /**
     * 기간 축소로 범위를 벗어난 블록 id 목록 (PRJ-02 movedToPool).
     * 벌크 UPDATE는 개수만 돌려주므로, 응답·op에 실을 id는 UPDATE 전에 먼저 확보해야 한다.
     */
    @Query("""
            SELECT b.id
            FROM Block b
            WHERE b.project.id = :projectId
              AND b.startOffsetMinutes >= :tripMinutes
              AND b.deletedAt IS NULL
            """)
    List<Long> findIdsOutOfRange(@Param("projectId") Long projectId, @Param("tripMinutes") int tripMinutes);

    /**
     * 지정한 블록들을 후보(POOL)로 일괄 이동 — 오프셋만 비우고 orderKey는 유지한다.
     *
     * 범위 조건이 아니라 id 목록으로 제한하는 이유: findIdsOutOfRange의 SELECT와 이
     * UPDATE 사이에 범위 밖으로 이동/생성된 블록이 있으면, 범위 조건 UPDATE는 그것까지
     * POOL로 보내면서 응답·op의 movedToPool에는 빠뜨린다 — 클라이언트가 모르는 변경이
     * 생겨 화면이 어긋난다. id로 제한하면 "응답에 실은 것만 변경"이 항상 성립한다.
     *
     * clearAutomatically로 영속성 컨텍스트가 비워지므로 다른 엔티티 변경이 끝난 뒤 마지막에 부른다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Block b
            SET b.startOffsetMinutes = null
            WHERE b.id IN :ids
            """)
    int moveToPoolByIds(@Param("ids") List<Long> ids);
}
