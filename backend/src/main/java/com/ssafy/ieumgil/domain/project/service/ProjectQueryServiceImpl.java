package com.ssafy.ieumgil.domain.project.service;

import com.ssafy.ieumgil.domain.activitylog.repository.ActivityLogRepository;
import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.project.converter.ProjectConverter;
import com.ssafy.ieumgil.domain.project.dto.ProjectResDTO;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.project.exception.ProjectErrorCode;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import com.ssafy.ieumgil.global.exception.CustomException;
import com.ssafy.ieumgil.global.websocket.PresenceRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectQueryServiceImpl implements ProjectQueryService {

    private final ProjectRepository projectRepository;
    private final BlockRepository blockRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ActivityLogRepository activityLogRepository;
    private final PresenceRegistry presenceRegistry;

    /** 그룹의 프로젝트 카드 목록 (PRJ-04) */
    @Override
    public List<ProjectResDTO.Card> getGroupProjects(Long userId, Long groupId) {
        return projectRepository.findByTravelGroupIdAndDeletedAtIsNullOrderByIdDesc(groupId)
                .stream()
                .map(ProjectConverter::toCard)
                .toList();
    }

    /**
     * 대시보드 스냅샷 (DSH-01). 최초 로딩과 재연결 복구가 같은 경로를 탄다.
     *
     * 쿼리는 블록·멤버 수와 무관하게 4회 고정이다 —
     * 프로젝트 1 + 블록 체인 1 + 멤버(JOIN FETCH) 1 + lastSeq 1.
     * 블록마다 작성자를 조회하면 N+1이 되므로 authorId는 FK 값(LAZY 프록시의 id)만 읽는다.
     */
    @Override
    public ProjectResDTO.Snapshot getSnapshot(Long userId, Long projectId) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new CustomException(ProjectErrorCode.PROJECT_NOT_FOUND));

        // groupId는 FK 값이라 LAZY 프록시에서 추가 쿼리 없이 읽힌다
        Long groupId = project.getTravelGroup().getId();

        List<Block> blocks = blockRepository.findChain(projectId);
        List<GroupMember> members = groupMemberRepository.findAllWithUserByGroupIdIn(List.of(groupId));
        long lastSeq = activityLogRepository.findLastSeq(projectId);

        // online: presence 레지스트리(WS 구독 기준) 실값 — Step 5에서 false 고정 해소
        return ProjectConverter.toSnapshot(project, blocks, members, lastSeq,
                presenceRegistry.onlineMembers(projectId));
    }
}
