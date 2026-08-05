package com.ssafy.ieumgil.global.websocket;

import com.ssafy.ieumgil.domain.auth.service.AuthCommandService;
import com.ssafy.ieumgil.domain.group.dto.GroupReqDTO;
import com.ssafy.ieumgil.domain.group.entity.GroupMember;
import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.group.service.GroupCommandService;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.verify;

/**
 * 권한이 사라진 뒤 WS 세션을 실제로 끊는지 확인한다(S2).
 *
 * <p>REST만 막는 것으로는 부족하다 — 이미 성립한 구독은 브로커가 직접 밀어주므로
 * StompAuthInterceptor를 타지 않고, 프레임을 하나도 보내지 않는 세션은 토큰이 만료돼도
 * 수신이 이어진다. 실제로 끊으려면 WsSessionRegistry.disconnect가 불려야 한다.
 *
 * <p>레지스트리는 목으로 둔다 — 진짜 WS 연결을 세우지 않고 "끊으라고 지시했는지"만 보면
 * 되고, 실제 종료 동작은 WsSessionRegistryTest가 따로 검증한다.
 */
class WsSessionTerminationIntegrationTest extends IntegrationTestSupport {

    @Autowired
    AuthCommandService authCommandService;
    @Autowired
    GroupCommandService groupCommandService;

    @MockitoBean
    WsSessionRegistry wsSessionRegistry;

    private TravelGroup seedGroupWith(User... members) {
        TravelGroup group = travelGroupRepository.save(TravelGroup.builder()
                .name("세션 종료 테스트 그룹")
                .inviteCode(UUID.randomUUID().toString().substring(0, 8))
                .inviteExpiresAt(LocalDateTime.now().plusDays(7))
                .build());
        for (User member : members) {
            groupMemberRepository.save(GroupMember.builder().travelGroup(group).user(member).build());
        }
        return group;
    }

    @Test
    @DisplayName("로그아웃하면 그 사용자의 WS 세션을 끊는다 — refresh 삭제만으로는 실시간 수신이 이어진다")
    void logoutDisconnectsWsSession() {
        User user = seedUser();

        authCommandService.logout(user.getId());

        verify(wsSessionRegistry).disconnect(user.getId());
    }

    @Test
    @DisplayName("그룹을 삭제하면 요청자뿐 아니라 전 멤버의 WS 세션을 끊는다")
    void groupDeletionDisconnectsAllMembers() {
        User owner = seedUser();
        User other1 = seedUser();
        User other2 = seedUser();
        TravelGroup group = seedGroupWith(owner, other1, other2);

        groupCommandService.softDeleteGroup(owner.getId(), group.getId(),
                new GroupReqDTO.Delete(group.getName()));

        // 요청자만 끊으면 나머지는 삭제된 그룹의 op·커서를 계속 받는다 —
        // 이미 성립한 구독은 인가가 다시 평가되지 않기 때문이다
        verify(wsSessionRegistry).disconnect(owner.getId());
        verify(wsSessionRegistry).disconnect(other1.getId());
        verify(wsSessionRegistry).disconnect(other2.getId());
    }
}
