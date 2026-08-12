package com.ssafy.ieumgil.domain.activitylog.service;

import java.util.List;
import java.util.Map;

public interface ActivityLogQueryService {

    List<Map<String, Object>> getOpsAfter(Long projectId, long afterSeq);
}
