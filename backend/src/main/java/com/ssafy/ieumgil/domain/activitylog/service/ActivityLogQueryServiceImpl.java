package com.ssafy.ieumgil.domain.activitylog.service;

import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityLogQueryServiceImpl implements ActivityLogQueryService {

    private final ActivityLogRepository activityLogRepository;

    /**
     * 유실 op 재전송 (NFR-01). 클라이언트가 seq 갭을 감지하거나 재연결했을 때
     * afterSeq 이후의 op를 순서대로 받아 상태를 따라잡는다.
     * 저장 전문을 그대로 돌려주므로 실시간 수신분과 형태가 완전히 같다.
     */
    @Override
    public List<Map<String, Object>> getOpsAfter(Long userId, Long projectId, long afterSeq) {
        return activityLogRepository.findOpsAfter(projectId, afterSeq);
    }
}
