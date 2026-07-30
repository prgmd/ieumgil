package com.ssafy.ieumgil.domain.activitylog.repository;

import com.ssafy.ieumgil.domain.activitylog.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /**
     * 프로젝트의 마지막 seq — 스냅샷의 lastSeq.
     * op가 하나도 없으면 0을 돌려줘 클라이언트가 afterSeq=0부터 동기화를 시작할 수 있게 한다.
     */
    @Query("SELECT COALESCE(MAX(a.seq), 0) FROM ActivityLog a WHERE a.project.id = :projectId")
    long findLastSeq(@Param("projectId") Long projectId);
}
