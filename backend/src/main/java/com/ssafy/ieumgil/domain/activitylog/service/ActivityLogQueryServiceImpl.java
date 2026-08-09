package com.ssafy.ieumgil.domain.activitylog.service;

import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityLogQueryServiceImpl implements ActivityLogQueryService {

    /** 한 번의 따라잡기 조회로 반환하는 op 상한 — 저널 전량이 힙에 올라오는 것을 막는다. */
    private static final int MAX_OPS_PER_FETCH = 1000;

    private final ActivityLogRepository activityLogRepository;

    /**
     * 유실 op 재전송 (NFR-01). 클라이언트가 seq 갭을 감지하거나 재연결했을 때
     * afterSeq 이후의 op를 순서대로 받아 상태를 따라잡는다.
     * 저장 전문을 그대로 돌려주므로 실시간 수신분과 형태가 완전히 같다.
     * 한 번에 최대 MAX_OPS_PER_FETCH개까지만 돌려주며, 더 남았으면 클라이언트가
     * 마지막 seq로 다시 요청해 이어받는다.
     */
    @Override
    public List<Map<String, Object>> getOpsAfter(Long userId, Long projectId, long afterSeq) {
        return activityLogRepository.findOpsAfter(projectId, afterSeq,
                PageRequest.of(0, MAX_OPS_PER_FETCH));
    }
}
