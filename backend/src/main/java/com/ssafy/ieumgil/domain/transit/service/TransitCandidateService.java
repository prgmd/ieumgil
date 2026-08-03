package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;

import java.util.List;

public interface TransitCandidateService {

    /**
     * 블록 사이 구간마다 이동수단 후보를 계산한다.
     *
     * <p>블록을 만들지 않는다 — 후보만 준비하고 생성은 사용자의 선택에 맡긴다.
     */
    TransitCandidateResDTO.Result calculate(Long projectId, List<Long> blockIds);
}
