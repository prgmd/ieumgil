package com.ssafy.ieumgil.domain.block.repository;

import com.ssafy.ieumgil.domain.block.entity.Block;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BlockRepository extends JpaRepository<Block, Long> {

    /**
     * 프로젝트의 살아있는 블록 전체 — 스냅샷용.
     * 정렬은 반드시 (orderKey, id) — 동시 삽입으로 orderKey가 같아질 수 있어 id로 tie-break 한다.
     * 이 쿼리의 (project_id, deleted_at IS NULL) 조건이 partial index ix_block_chain의 존재 이유다.
     */
    @Query("""
            SELECT b
            FROM Block b
            WHERE b.project.id = :projectId
              AND b.deletedAt IS NULL
            ORDER BY b.orderKey ASC, b.id ASC
            """)
    List<Block> findChain(@Param("projectId") Long projectId);
}
