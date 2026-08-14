package com.ssafy.ieumgil.domain.block.service;

import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;

public interface DetailLockService {

    BlockResDTO.LockResult acquire(Long userId, Long blockId);

    BlockResDTO.LockHeartbeat heartbeat(Long userId, Long blockId);

    void release(Long userId, Long blockId);

    /**
     * 다른 멤버가 이 블록의 detail 편집 락을 쥐고 있는지 — detail 쓰기 전 서버 측 강제용.
     * 락이 없거나 요청자 본인의 락이면 false.
     */
    boolean isLockedByOther(Long userId, Long blockId);
}
