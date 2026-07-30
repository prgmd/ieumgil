package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.entity.Festival;

import java.time.LocalDate;
import java.util.List;

public interface FestivalQueryService {

    List<Festival> findByRegionAndDateRange(String lDongRegnCd, LocalDate tripStartDate, LocalDate tripEndDate);
}
