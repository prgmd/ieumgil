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
    public List<ProjectResDTO.Card> getGroupProjects(Long groupId) {
        return projectRepository.findByTravelGroupIdAndDeletedAtIsNullOrderByIdDesc(groupId)
                .stream()
                .map(ProjectConverter::toCard)
                .toList();
    }

    /**
     * 대시보드 스냅샷 (DSH-01). 최초 로딩과 재연결 복구가 같은 경로를 탄다.
     *
     * 쿼리는 블록·멤버 수와 무관하게 4회 고정이다 —
     * 프로젝트 1 + lastSeq 1 + 블록 체인 1 + 멤버(JOIN FETCH) 1.
     * 블록마다 작성자를 조회하면 N+1이 되므로 authorId는 FK 값(LAZY 프록시의 id)만 읽는다.
     */
    @Override
    public ProjectResDTO.Snapshot getSnapshot(Long projectId) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new CustomException(ProjectErrorCode.PROJECT_NOT_FOUND));

        // groupId는 FK 값이라 LAZY 프록시에서 추가 쿼리 없이 읽힌다
        Long groupId = project.getTravelGroup().getId();

        // ⚠️ lastSeq를 blocks보다 먼저 읽어야 한다. READ COMMITTED에서는 쿼리마다 스냅샷이
        // 따로라, blocks를 먼저 읽으면 두 쿼리 사이에 커밋된 op가 lastSeq에는 포함되고
        // blocks에는 없는 상태가 된다 — 클라이언트가 그 op의 브로드캐스트를 seq ≤ lastSeq로
        // 스킵해서 해당 블록이 새로고침 전까지 안 보인다. 반대 순서면 그 op는 lastSeq보다
        // 커서 정상 적용되고, blocks에 이미 반영돼 있어도 op가 전부 멱등이라 무해하다.
        long lastSeq = activityLogRepository.findLastSeq(projectId);
        List<Block> blocks = blockRepository.findChain(projectId);
        List<GroupMember> members = groupMemberRepository.findAllWithUserByGroupIdIn(List.of(groupId));

        // online: presence 레지스트리(WS 구독 기준) 실값 — Step 5에서 false 고정 해소
        return ProjectConverter.toSnapshot(project, blocks, members, lastSeq,
                presenceRegistry.onlineMembers(projectId));
    }
}
