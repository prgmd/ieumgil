package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.project.dto.ProjectReqDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.entity.TransportPref;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.global.realtime.OpPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 프로젝트 수정 커맨드의 부분 수정(PATCH)·op 발행을 DB 없이 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class ProjectCommandServiceImplTest {

    @Mock
    ProjectRepository projectRepository;
    @Mock
    TravelGroupRepository travelGroupRepository;
    @Mock
    BlockRepository blockRepository;
    @Mock
    OpPublisher opPublisher;

    ProjectCommandServiceImpl service;

    Project project;

    @BeforeEach
    void setUp() {
        service = new ProjectCommandServiceImpl(
                projectRepository, travelGroupRepository, blockRepository, opPublisher);
        project = Project.builder()
                .id(1L)
                .name("여행")
                .transportPrefs(List.of(TransportPref.PUBLIC))
                .build();
    }

    @Test
    @DisplayName("교통수단 선호 수정이 저장되고 PROJECT_UPDATED op에 실린다")
    void updateTransportPrefs_persistsAndPublishesOp() {
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));

        service.updateProject(1L, 1L, "client-1",
                new ProjectReqDTO.Update(null, null, null,
                        List.of(TransportPref.CAR, TransportPref.PUBLIC)));

        assertThat(project.getTransportPrefs())
                .containsExactlyInAnyOrder(TransportPref.CAR, TransportPref.PUBLIC);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(opPublisher).publish(eq(1L), eq(1L), eq("client-1"), eq("PROJECT_UPDATED"), payload.capture());
        assertThat(payload.getValue()).containsEntry("transportPrefs", List.of("CAR", "PUBLIC"));
    }
}
