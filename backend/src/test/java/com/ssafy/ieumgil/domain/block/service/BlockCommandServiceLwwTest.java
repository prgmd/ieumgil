package com.ssafy.ieumgil.domain.block.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.ieumgil.domain.block.converter.BlockConverter;
import com.ssafy.ieumgil.domain.block.dto.BlockReqDTO;
import com.ssafy.ieumgil.domain.block.dto.BlockResDTO;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.exception.BlockErrorCode;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.domain.user.repository.UserRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.global.realtime.OpPublisher;
import com.ssafy.ieumgil.support.BlockFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 필드 단위 LWW 갱신의 판정/검증 로직 단위 테스트 — DB 없이 규칙만 검증한다.
 * 실제 동시 실행에서의 보존(행 잠금)은 BlockConcurrencyIntegrationTest가 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class BlockCommandServiceLwwTest {

    @Mock
    BlockRepository blockRepository;
    @Mock
    ProjectRepository projectRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    OpPublisher opPublisher;
    // 기본 스텁(isLockedByOther=false)으로 락 비보유 상태를 재현 — 락 강제 자체는
    // DetailLockIntegrationTest가 실제 Redis로 검증한다
    @Mock
    DetailLockService detailLockService;

    BlockCommandServiceImpl service;

    Block block;

    @BeforeEach
    void setUp() {
        service = new BlockCommandServiceImpl(
                blockRepository, projectRepository, userRepository, opPublisher, new ObjectMapper(),
                detailLockService);
        block = Block.builder()
                .id(10L)
                .name("성산일출봉")
                .category(BlockCategory.SPOT)
                .orderKey("a0")
                .source(BlockSource.MANUAL)
                .project(Project.builder().id(1L).name("여행").build())
                .fieldUpdatedAt(new HashMap<>())
                .build();
    }

    private BlockResDTO.FieldsApplied patch(String field, Object value) {
        when(blockRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(block));
        return service.updateFields(1L, 10L, "client-1",
                new BlockReqDTO.UpdateFields(List.of(new BlockReqDTO.FieldChange(field, value))));
    }

    @Test
    @DisplayName("신선한 필드는 반영되고 op가 발행된다")
    void freshFieldApplied() {
        BlockResDTO.FieldsApplied result = patch("budget", 15000);

        assertThat(result.applied()).containsEntry("budget", true);
        assertThat(block.getBudget()).isEqualTo(15000);
        assertThat(block.getFieldUpdatedAt()).containsKey("budget");
        verify(opPublisher).publish(eq(1L), eq(1L), eq("client-1"), eq("BLOCK_FIELD_UPDATED"), anyMap());
    }

    @Test
    @DisplayName("스테일 필드(더 최신 타임스탬프 보유)는 applied:false로 무시되고 op가 없다")
    void staleFieldIgnored() {
        block.getFieldUpdatedAt().put("budget", Instant.now().plusSeconds(3600).toString());

        BlockResDTO.FieldsApplied result = patch("budget", 15000);

        assertThat(result.applied()).containsEntry("budget", false);
        assertThat(block.getBudget()).isZero();
        verify(opPublisher, never()).publish(anyLong(), anyLong(), any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("스테일과 신선이 섞이면 신선한 필드만 op payload에 실린다")
    void mixedFreshAndStale() {
        block.getFieldUpdatedAt().put("name", Instant.now().plusSeconds(3600).toString());
        when(blockRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(block));

        BlockResDTO.FieldsApplied result = service.updateFields(1L, 10L, null,
                new BlockReqDTO.UpdateFields(List.of(
                        new BlockReqDTO.FieldChange("name", "새이름"),
                        new BlockReqDTO.FieldChange("budget", 9000))));

        assertThat(result.applied()).containsEntry("name", false).containsEntry("budget", true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(opPublisher).publish(eq(1L), eq(1L), any(), eq("BLOCK_FIELD_UPDATED"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) payload.getValue().get("fields");
        assertThat(fields).containsOnlyKeys("budget");
    }

    @Test
    @DisplayName("화이트리스트 밖 필드는 400(UNSUPPORTED_FIELD) — orderKey·시각")
    void unsupportedFieldRejected() {
        assertThatThrownBy(() -> patch("orderKey", "z9"))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.UNSUPPORTED_FIELD);
        // 시각은 더 이상 LWW 필드가 아니다 — 값이 형식 위반이어도 "없는 필드"가 먼저 걸린다
        assertThatThrownBy(() -> patch("startTime", "25:99"))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.UNSUPPORTED_FIELD);
    }

    @Test
    @DisplayName("값 형식 위반은 400(INVALID_FIELD_VALUE) — 빈 이름/음수 예산/0분")
    void invalidValuesRejected() {
        assertThatThrownBy(() -> patch("name", "  "))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.INVALID_FIELD_VALUE);
        assertThatThrownBy(() -> patch("budget", -1))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.INVALID_FIELD_VALUE);
        assertThatThrownBy(() -> patch("durationMin", 0))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.INVALID_FIELD_VALUE);
    }

    @Test
    @DisplayName("vehicleFlag는 ETC가 아닌 블록에 지정할 수 없다")
    void vehicleFlagOnlyForEtc() {
        assertThatThrownBy(() -> patch("vehicleFlag", "START"))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.VEHICLE_FLAG_NOT_ALLOWED);
    }

    @Test
    @DisplayName("생성 시 장소성 카테고리는 좌표가 없으면 400(COORDINATE_REQUIRED)")
    void coordinateRequiredForSpot() {
        BlockReqDTO.Create request = BlockFixtures.pool(BlockCategory.SPOT, "성산일출봉", null);

        assertThatThrownBy(() -> service.createBlock(1L, 1L, null, request))
                .isInstanceOf(CustomException.class)
                .extracting("code").isEqualTo(BlockErrorCode.COORDINATE_REQUIRED);
    }

    @Test
    @DisplayName("생성 요청의 detail이 저장된다 — 챗봇 축제 후보의 개최 기간처럼 생성 시점에 아는 정보를 담는다")
    void createPersistsDetail() {
        BlockReqDTO.Create request = BlockFixtures.spot("부산 불꽃축제",
                new java.math.BigDecimal("35.15"), new java.math.BigDecimal("129.11"),
                BlockSource.BOT, "개최 기간: 2026-10-04 ~ 2026-10-14");

        Block block = BlockConverter.toBlock(null, null, "a0", request);

        assertThat(block.getDetail()).isEqualTo("개최 기간: 2026-10-04 ~ 2026-10-14");
    }
}
