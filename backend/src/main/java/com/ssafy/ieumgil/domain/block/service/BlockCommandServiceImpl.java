package com.ssafy.ieumgil.domain.block.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.block.converter.BlockConverter;
import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.VehicleFlag;
import com.ssafy.ieumgil.domain.block.exception.BlockErrorCode;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.global.realtime.OpPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional
public class BlockCommandServiceImpl implements BlockCommandService {

    /** LWW 갱신을 허용하는 필드 화이트리스트 — Block.applyField의 switch와 1:1 */
    private static final Set<String> LWW_FIELDS = Set.of(
            "name", "budget", "durationMin", "detail",
            "isTimeFixed", "vehicleFlag", "transportMeta");

    private final BlockRepository blockRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final OpPublisher opPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 블록 생성 (MAP-03/04, BLK-04). 저장과 op 기록이 한 트랜잭션 —
     * "저널에는 있는데 블록은 없는" 어긋남이 생기지 않는다.
     */
    @Override
    public BlockResDTO.Created createBlock(Long userId, Long projectId, String clientId, BlockReqDTO.Create request) {
        // 장소성·vehicleFlag 규칙은 카테고리와의 교차 검증이라 애노테이션으로 못 건다
        if (request.category().requiresCoordinate() && (request.lat() == null || request.lng() == null)) {
            throw new CustomException(BlockErrorCode.COORDINATE_REQUIRED);
        }
        if (request.vehicleFlag() != null && request.category() != BlockCategory.ETC) {
            throw new CustomException(BlockErrorCode.VEHICLE_FLAG_NOT_ALLOWED);
        }

        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new CustomException(ProjectErrorCode.PROJECT_NOT_FOUND));

        String orderKey = request.orderKey() != null
                ? request.orderKey()
                : tailOrderKey(projectId);

        Block block = blockRepository.save(
                BlockConverter.toBlock(project, userRepository.getReferenceById(userId), orderKey, request));

        // 수신 측이 생성 즉시 렌더링할 수 있도록 op payload에 블록 전문을 싣는다
        long seq = opPublisher.publish(projectId, userId, clientId, "BLOCK_CREATED",
                Map.of("blockId", block.getId(), "block", toPayloadMap(block)));

        return BlockResDTO.Created.builder().blockId(block.getId()).seq(seq).build();
    }

    /**
     * 필드 단위 LWW 배치 갱신 (BLK-05/06).
     *
     * 판정 기준은 서버 수신 시각이다 — 클라이언트 시계는 신뢰하지 않는다.
     * 스테일 필드는 에러가 아니라 applied:false로 조용히 버린다: 더 최신 변경이 이미
     * 반영돼 있다는 뜻이므로, 되살리면 오히려 다른 사용자의 변경을 되돌리게 된다.
     *
     * 알려진 한계(감수): 같은 블록을 두 트랜잭션이 동시에 고치면 fieldUpdatedAt 맵 전체가
     * 나중 커밋으로 덮여 앞 트랜잭션의 타임스탬프가 유실될 수 있다. 정확성이 필요해지면
     * jsonb_set 네이티브 쿼리로 필드 단위 원자 갱신으로 교체한다.
     */
    @Override
    public BlockResDTO.FieldsApplied updateFields(Long userId, Long blockId, String clientId,
                                                  BlockReqDTO.UpdateFields request) {
        Block block = getAliveBlock(blockId);
        Instant receivedAt = Instant.now();

        Map<String, Boolean> applied = new LinkedHashMap<>();
        Map<String, Object> appliedValues = new LinkedHashMap<>();   // op payload용 — 적용분만

        for (BlockReqDTO.FieldChange change : request.fields()) {
            String field = change.field();
            if (!LWW_FIELDS.contains(field)) {
                throw new CustomException(BlockErrorCode.UNSUPPORTED_FIELD);
            }

            String prev = block.getFieldUpdatedAt().get(field);
            boolean isFresh = prev == null || Instant.parse(prev).isBefore(receivedAt);

            if (isFresh) {
                Object parsed = parseFieldValue(block, field, change.value());
                block.applyField(field, parsed);
                block.markFieldUpdated(field, receivedAt);
                appliedValues.put(field, change.value());   // 원본 JSON 값 그대로 — 수신 측 표현과 일치
            }
            applied.put(field, isFresh);
        }

        // 전부 스테일이면 op를 만들지 않는다 — seq 낭비 + 의미 없는 브로드캐스트 방지.
        // 마지막 편집자 기록도 같은 조건 — 스테일만 보낸 요청은 편집으로 치지 않아야
        // op 기반의 클라이언트 배지 기록과 어긋나지 않는다(PRS-04)
        if (!appliedValues.isEmpty()) {
            block.markEditedBy(userRepository.getReferenceById(userId));
            opPublisher.publish(block.getProject().getId(), userId, clientId, "BLOCK_FIELD_UPDATED",
                    Map.of("blockId", blockId, "fields", appliedValues));
        }

        return BlockResDTO.FieldsApplied.builder().applied(applied).build();
    }

    /** 블록 이동 (BLK-07, DAY-02) — 옮긴 1행만 UPDATE. 목적지는 절대 오프셋 하나로 온다 */
    @Override
    public BlockResDTO.Moved move(Long userId, Long blockId, String clientId, BlockReqDTO.Move request) {
        Block block = getAliveBlock(blockId);

        block.move(request.startOffsetMinutes(), request.orderKey());
        // 이동도 편집이다 — 클라이언트가 BLOCK_MOVED의 actorId를 배지로 기록하는 것과 맞춘다(PRS-04)
        block.markEditedBy(userRepository.getReferenceById(userId));

        long seq = opPublisher.publish(block.getProject().getId(), userId, clientId, "BLOCK_MOVED",
                payloadWithNullable("blockId", blockId,
                        "startOffsetMinutes", request.startOffsetMinutes(), "orderKey", request.orderKey()));

        return BlockResDTO.Moved.builder().blockId(blockId).seq(seq).build();
    }

    /** 블록 소프트 삭제 (BLK-09) — tombstone. 이미 삭제된 블록의 중복 삭제도 410이다 */
    @Override
    public void softDelete(Long userId, Long blockId, String clientId) {
        Block block = getAliveBlock(blockId);

        block.softDelete();

        opPublisher.publish(block.getProject().getId(), userId, clientId, "BLOCK_DELETED",
                Map.of("blockId", blockId));
    }

    // ----- 공통 -----

    /**
     * 살아있는 블록만 돌려준다. 없으면 404, tombstone이면 410 —
     * "없었다"와 "지워졌다"를 구분해야 프론트가 해당 블록만 조용히 제거할 수 있다(BLK-09).
     *
     * 행 잠금(FOR UPDATE)으로 조회한다 — 같은 블록의 동시 수정에서 마지막 커밋이
     * 다른 필드까지 덮어 유실시키는 것을 실측으로 확인해서다(동시 5필드 중 4필드 유실).
     * 잠금 범위는 블록 한 행·수 ms라 다른 블록 편집에는 영향이 없다.
     */
    private Block getAliveBlock(Long blockId) {
        Block block = blockRepository.findByIdForUpdate(blockId)
                .orElseThrow(() -> new CustomException(BlockErrorCode.BLOCK_NOT_FOUND));
        if (block.isDeleted()) {
            throw new CustomException(BlockErrorCode.BLOCK_GONE);
        }
        return block;
    }

    /**
     * orderKey 미지정 시 프로젝트 체인의 말단 키 뒤에 붙인다. Day 스코프는 없다 —
     * 보드 순서는 절대 오프셋이 정하고 orderKey는 tie-break만 맡는다.
     * "V"는 fractional index 알파벳의 중간값 — 어떤 키 k에 대해서도 k+"V" > k (사전순)이므로
     * 항상 말단이 된다. 서버 부여가 반복되면 키가 길어지지만, 정식 키 계산은 클라이언트
     * 몫이고 이 경로는 폴백이라 감수한다.
     */
    private String tailOrderKey(Long projectId) {
        return blockRepository.findTopByProject_IdAndDeletedAtIsNullOrderByOrderKeyDescIdDesc(projectId)
                .map(block -> block.getOrderKey() + "V")
                .orElse("a0");
    }

    /** JSON 원시 값을 필드 타입으로 파싱·검증한다. 실패는 전부 400(INVALID_FIELD_VALUE)로 통일 */
    private Object parseFieldValue(Block block, String field, Object raw) {
        try {
            return switch (field) {
                case "name" -> {
                    String name = (String) raw;
                    if (name == null || name.isBlank() || name.length() > 255) {
                        throw new CustomException(BlockErrorCode.INVALID_FIELD_VALUE);
                    }
                    yield name;
                }
                case "detail" -> {
                    String detail = (String) raw;
                    if (detail != null && detail.length() > 500) {
                        throw new CustomException(BlockErrorCode.INVALID_FIELD_VALUE);
                    }
                    yield detail;
                }
                case "budget", "durationMin" -> {
                    int number = ((Number) raw).intValue();
                    if (number < 0 || (field.equals("durationMin") && number == 0)) {
                        throw new CustomException(BlockErrorCode.INVALID_FIELD_VALUE);
                    }
                    yield number;
                }
                case "isTimeFixed" -> (Boolean) raw;
                case "vehicleFlag" -> {
                    if (raw == null) {
                        yield null;
                    }
                    if (block.getCategory() != BlockCategory.ETC) {
                        throw new CustomException(BlockErrorCode.VEHICLE_FLAG_NOT_ALLOWED);
                    }
                    yield VehicleFlag.valueOf((String) raw);
                }
                case "transportMeta" -> raw;   // 자유 구조 JSON — 스키마 검증 없이 통과(ERD 예시 참조)
                default -> throw new CustomException(BlockErrorCode.UNSUPPORTED_FIELD);
            };
        } catch (ClassCastException | IllegalArgumentException e) {
            throw new CustomException(BlockErrorCode.INVALID_FIELD_VALUE);
        }
    }

    /** BLOCK_CREATED payload용 — 블록 전문을 맵으로 (스냅샷 Item과 같은 모양) */
    private Map<String, Object> toPayloadMap(Block block) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = objectMapper.convertValue(BlockConverter.toItem(block), Map.class);
        return map;
    }

    /** Map.of는 null 값을 거부하므로(startOffsetMinutes null = POOL 이동) 직접 담는다 */
    private static Map<String, Object> payloadWithNullable(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
