package com.ssafy.ieumgil.global.websocket;

import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WS 인가 판정이 <b>그룹 생존</b>까지 보는지 확인한다.
 *
 * <p>그룹 소프트 삭제는 프로젝트를 함께 지우지 않고 멤버십 행도 남긴다. 프로젝트만 확인하면
 * 삭제된 그룹의 대시보드를 계속 인가하게 되는데, REST(@GroupMember)는 그룹 생존을 먼저 보므로
 * 두 경로의 기준이 어긋난다 — 그 틈이 곧 WS 우회로다.
 *
 * <p>그룹 삭제 시 열린 세션은 GroupCommandService가 끊는다(WsSessionTerminationIntegrationTest).
 * 여기서 막는 것은 그 뒤의 <b>재연결</b>이다 — 둘이 함께 있어야 실제로 차단된다.
 */
class ProjectMembershipIntegrationTest extends IntegrationTestSupport {

    @Autowired
    ProjectMembership projectMembership;

    @Test
    @DisplayName("살아있는 그룹의 멤버는 인가된다 — 정상 흐름")
    void memberOfAliveGroupIsAuthorized() {
        User member = seedUser();
        Project project = seedProject(member);

        assertThat(projectMembership.isMember(project.getId(), member.getId())).isTrue();
    }

    @Test
    @DisplayName("멤버가 아니면 인가되지 않는다 — 기존 판정은 그대로다")
    void nonMemberIsRejected() {
        User member = seedUser();
        User outsider = seedUser();
        Project project = seedProject(member);

        assertThat(projectMembership.isMember(project.getId(), outsider.getId())).isFalse();
    }

    @Test
    @DisplayName("그룹이 삭제되면 멤버였어도 인가하지 않는다 — 삭제된 그룹의 재구독 차단")
    void memberOfDeletedGroupIsRejected() {
        User member = seedUser();
        Project project = seedProject(member);

        TravelGroup group = project.getTravelGroup();
        group.softDelete();
        travelGroupRepository.save(group);

        // 멤버십 행과 프로젝트는 그대로 남아 있다 — 그룹만 보고 걸러야 한다
        assertThat(groupMemberRepository.existsMembership(group.getId(), member.getId())).isTrue();
        assertThat(projectRepository.findByIdAndDeletedAtIsNull(project.getId())).isPresent();

        assertThatThrownBy(() -> projectMembership.isMember(project.getId(), member.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("삭제된 프로젝트");
    }
}
