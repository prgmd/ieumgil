package com.ssafy.ieumgil.domain.transit.service;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;

import java.util.List;

public interface TransitCandidateService {

    /**
     * 블록 사이 구간마다 이동수단 후보를 계산한다.
     *
     * <p>블록을 만들지 않는다 — 후보만 준비하고 생성은 사용자의 선택에 맡긴다.
     *
     * <p>시각을 따로 받지 않는다. 시외 출발편의 기준 시각은 구간마다
     * {@code from 블록의 startTime + durationMin}으로 독립적으로 구한다 — 블록에 이미 있는
     * 진실을 클라이언트가 다시 알려줄 이유가 없고, 그래야 블록 사이 공백이 그대로 반영된다.
     */
    TransitCandidateResDTO.Result calculate(Long projectId, List<Long> blockIds);
}
