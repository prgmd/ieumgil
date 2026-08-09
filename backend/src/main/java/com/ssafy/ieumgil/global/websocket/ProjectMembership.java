package com.ssafy.ieumgil.global.websocket;

import com.ssafy.ieumgil.domain.group.repository.GroupMemberRepository;
import com.ssafy.ieumgil.domain.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 멤버십 판정 — WS 인가의 단일 출처.
 *
 * StompAuthInterceptor는 <b>프레임을 보내는 쪽</b>을, RealtimeRelayController는
 * <b>시그널을 받는 쪽</b>을 검사한다. 둘이 각자 project → group → membership 체인을
 * 들고 있으면 멤버십 규칙이 바뀔 때 한쪽만 고쳐지고, 그 한쪽이 보안 구멍이 된다.
 */
@Component
@RequiredArgsConstructor
public class ProjectMembership {

    private final ProjectRepository projectRepository;
    private final GroupMemberRepository groupMemberRepository;

    /**
     * 그룹이 삭제되면 프로젝트도 접근 불가로 본다 — REST(@GroupMember)가 그룹 생존을 먼저
     * 확인하는 것과 같은 기준이다. 그룹 소프트 삭제는 프로젝트를 지우지 않고 멤버십 행도
     * 남기므로, 프로젝트만 보면 삭제된 그룹의 대시보드를 다시 구독할 수 있다
     * (기존 세션은 GroupCommandService가 끊지만, 재연결까지 막는 것은 이 판정의 몫이다).
     *
     * @return 해당 프로젝트가 속한 그룹의 멤버면 true
     * @throws IllegalArgumentException 프로젝트나 그룹이 없거나 삭제된 경우 — "없는 프로젝트"와
     *                                  "비멤버"는 원인이 다르므로 호출부가 구분할 수 있어야 한다
     */
    public boolean isMember(Long projectId, Long memberId) {
        Long groupId = projectRepository.findAliveGroupIdById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않거나 삭제된 프로젝트: " + projectId));
        return groupMemberRepository.existsMembership(groupId, memberId);
    }
}
