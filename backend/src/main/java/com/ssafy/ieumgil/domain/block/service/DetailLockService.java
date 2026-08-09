package com.ssafy.ieumgil.domain.block.service;

import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;

public interface DetailLockService {

    BlockResDTO.LockResult acquire(Long userId, Long blockId);

    BlockResDTO.LockHeartbeat heartbeat(Long userId, Long blockId);

    void release(Long userId, Long blockId);
}
