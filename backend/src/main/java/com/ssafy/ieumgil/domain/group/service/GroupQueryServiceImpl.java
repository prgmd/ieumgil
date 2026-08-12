package com.ssafy.ieumgil.domain.group.service;

import com.ssafy.ieumgil.domain.group.converter.GroupConverter;
import com.ssafy.ieumgil.domain.group.dto.GroupResDTO;
import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.group.repository.TravelGroupRepository;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GroupQueryServiceImpl implements GroupQueryService {

    private final TravelGroupRepository travelGroupRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final ProjectRepository projectRepository;

    /**
     * 내 그룹 목록 (MY-01). 그룹 수와 무관하게 쿼리 3번으로 끝낸다.
     * 그룹마다 멤버·프로젝트를 조회하면 N+1이 되므로 id 목록으로 일괄 조회한 뒤 자바에서 묶는다.
     */
    @Override
    public List<GroupResDTO.Summary> getMyGroups(Long userId) {
        List<TravelGroup> myGroups = travelGroupRepository.findMyGroups(userId);

        // IN 절에 빈 리스트를 넘기면 문법 오류가 되므로 아래 쿼리를 아예 던지지 않는다
        if (myGroups.isEmpty()) {
            return List.of();
        }

        List<Long> groupIds = myGroups.stream()
                .map(TravelGroup::getId)
                .toList();

        Map<Long, List<GroupMember>> membersByGroupId = groupMemberRepository.findAllWithUserByGroupIdIn(groupIds)
                .stream()
                .collect(Collectors.groupingBy(groupMember -> groupMember.getTravelGroup().getId()));

        // 프로젝트가 0개인 그룹은 GROUP BY 결과에 아예 없으므로 꺼낼 때 기본값 0을 준다
        Map<Long, Long> projectCountByGroupId = projectRepository.countByGroupIdIn(groupIds)
                .stream()
                .collect(Collectors.toMap(
                        ProjectRepository.GroupProjectCount::getGroupId,
                        ProjectRepository.GroupProjectCount::getProjectCount));

        return myGroups.stream()
                .map(group -> GroupConverter.toSummary(
                        group,
                        membersByGroupId.getOrDefault(group.getId(), List.of()),
                        projectCountByGroupId.getOrDefault(group.getId(), 0L)))
                .toList();
    }

    /**
     * 멤버 목록 + 초대 코드 (GRP-04).
     * 조회는 3단계에서 만든 목록용 메서드를 그룹 하나만 넣어 재사용한다.
     */
    @Override
    public GroupResDTO.MemberList getMembers(Long groupId) {
        TravelGroup group = travelGroupRepository.findAliveByIdOrThrow(groupId);

        List<GroupMember> members = groupMemberRepository.findAllWithUserByGroupIdIn(List.of(groupId));

        return GroupConverter.toMemberList(group, members);
    }
}
