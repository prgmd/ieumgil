package com.ssafy.ieumgil.domain.activitylog.repository;

import com.ssafy.ieumgil.domain.activitylog.entity.ActivityLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /**
     * 프로젝트의 마지막 seq — 스냅샷의 lastSeq.
     * op가 하나도 없으면 0을 돌려줘 클라이언트가 afterSeq=0부터 동기화를 시작할 수 있게 한다.
     */
    @Query("SELECT COALESCE(MAX(a.seq), 0) FROM ActivityLog a WHERE a.project.id = :projectId")
    long findLastSeq(@Param("projectId") Long projectId);

    /**
     * 유실 op 재전송(NFR-01) — 저장된 op 전문(payload)을 가공 없이 그대로 돌려준다.
     * 브로드캐스트했던 것과 바이트 단위로 같아야 하므로 여기서 어떤 변환도 하지 않는다.
     * (project_id, seq) UNIQUE 인덱스가 이 조회를 그대로 탄다.
     * 조회 상한은 Pageable로 건다 — afterSeq가 아주 오래된 값이면 저널 전량이 힙에 올라올 수
     * 있으므로, 한 번에 가져오는 op 수를 제한한다. 상한을 넘으면 클라이언트가 마지막 seq로
     * 다시 요청해 이어서 따라잡는다(seq 순서 보장).
     */
    @Query("""
            SELECT a.payload
            FROM ActivityLog a
            WHERE a.project.id = :projectId
              AND a.seq > :afterSeq
            ORDER BY a.seq ASC
            """)
    List<Map<String, Object>> findOpsAfter(@Param("projectId") Long projectId, @Param("afterSeq") long afterSeq,
                                           Pageable pageable);
}
