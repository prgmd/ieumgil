package com.ssafy.ieumgil.domain.festival.repository;

import com.ssafy.ieumgil.domain.festival.entity.Festival;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FestivalRepository extends JpaRepository<Festival, Long> {

    Optional<Festival> findByContentId(String contentId);

    @Query("SELECT f FROM Festival f WHERE f.lDongRegnCd = :regionCode "
            + "AND f.eventStartDate <= :tripEndDate AND f.eventEndDate >= :tripStartDate")
    List<Festival> findOverlapping(@Param("regionCode") String regionCode,
                                    @Param("tripStartDate") LocalDate tripStartDate,
                                    @Param("tripEndDate") LocalDate tripEndDate);

    /**
     * 종료일이 지난 축제를 정리한다 (배치 전용). FestivalBatchService는 TourAPI 호출과 뒤섞여
     * 도는 긴 흐름이라 전체를 하나의 트랜잭션으로 감쌀 수 없으므로, 이 메서드 자체에
     * {@code @Transactional}을 둔다 — 그렇지 않으면 저장소 프록시가 기본으로 씌우는
     * readOnly 트랜잭션 위에서 DELETE가 돌아 Postgres가 거부한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("DELETE FROM Festival f WHERE f.eventEndDate < :today")
    int deleteExpiredBefore(@Param("today") LocalDate today);
}
