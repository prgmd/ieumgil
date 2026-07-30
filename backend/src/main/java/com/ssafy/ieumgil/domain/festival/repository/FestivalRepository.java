package com.ssafy.ieumgil.domain.festival.repository;

import com.ssafy.ieumgil.domain.festival.entity.Festival;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
