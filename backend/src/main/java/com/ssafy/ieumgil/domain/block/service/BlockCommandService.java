package com.ssafy.ieumgil.domain.block.service;

import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;

public interface BlockCommandService {

    BlockResDTO.Created createBlock(Long userId, Long projectId, String clientId, BlockReqDTO.Create request);

    BlockResDTO.FieldsApplied updateFields(Long userId, Long blockId, String clientId, BlockReqDTO.UpdateFields request);

    BlockResDTO.Moved move(Long userId, Long blockId, String clientId, BlockReqDTO.Move request);

    void softDelete(Long userId, Long blockId, String clientId);
}
