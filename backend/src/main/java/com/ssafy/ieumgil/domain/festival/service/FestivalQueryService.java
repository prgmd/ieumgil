package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.entity.Festival;

import java.time.LocalDate;
import java.util.List;

public interface FestivalQueryService {

    List<Festival> findByRegionAndDateRange(String lDongRegnCd, LocalDate tripStartDate, LocalDate tripEndDate);

    /** 저장된 축제 공식 홈페이지 URL. 없으면 null이다(프론트가 카카오 검색으로 폴백). */
    String getHomepageUrl(String contentId);
}
