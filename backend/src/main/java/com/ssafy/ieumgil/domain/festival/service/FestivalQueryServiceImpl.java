package com.ssafy.ieumgil.domain.festival.service;

import com.ssafy.ieumgil.domain.festival.entity.Festival;
import com.ssafy.ieumgil.domain.festival.repository.FestivalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FestivalQueryServiceImpl implements FestivalQueryService {

    private final FestivalRepository festivalRepository;

    @Override
    public List<Festival> findByRegionAndDateRange(String lDongRegnCd, LocalDate tripStartDate, LocalDate tripEndDate) {
        return festivalRepository.findOverlapping(lDongRegnCd, tripStartDate, tripEndDate);
    }
}
