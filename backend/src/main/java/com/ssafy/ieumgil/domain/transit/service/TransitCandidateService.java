package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;

import java.time.LocalTime;
import java.util.List;

public interface TransitCandidateService {

    /**
     * 블록 사이 구간마다 이동수단 후보를 계산한다.
     *
     * <p>블록을 만들지 않는다 — 후보만 준비하고 생성은 사용자의 선택에 맡긴다.
     *
     * @param dayStart 그 Day의 시작 시각. null이면 09:00으로 본다. 시외 출발편 선정 기준의
     *                 출발점이 된다 — 프론트가 DAY-03으로 ±30분 조정하는 값이다.
     */
    TransitCandidateResDTO.Result calculate(Long projectId, List<Long> blockIds, LocalTime dayStart);
}
