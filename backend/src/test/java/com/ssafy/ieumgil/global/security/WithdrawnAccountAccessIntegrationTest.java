package com.ssafy.ieumgil.global.security;

import com.ssafy.ieumgil.domain.group.entity.TravelGroup;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.domain.user.service.UserCommandService;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴한 계정의 access 토큰 차단(S1) — 엔드투엔드 회귀 테스트.
 *
 * <p>탈퇴는 refresh 토큰만 지우므로 이미 발급된 access 토큰은 최대 30분 더 유효하다.
 * 인증 필터가 계정 생존을 확인하지 않으면 그 30분 동안 탈퇴한 계정이 그룹에 다시 들어가
 * 정원(10명) 한 자리를 "탈퇴한 사용자" 이름으로 점유한다 — joinGroup은 회원을 findById로만
 * 조회해서 탈퇴 여부를 보지 않기 때문에 서비스 계층에서도 걸리지 않는다.
 */
@AutoConfigureMockMvc
class WithdrawnAccountAccessIntegrationTest extends IntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    UserCommandService userCommandService;

    /** 초대 코드는 요청 DTO가 ^[A-Z0-9]{8}$로 검증한다 — 형식이 어긋나면 인가가 아니라 400이 난다 */
    private TravelGroup seedGroup() {
        String inviteCode = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return travelGroupRepository.save(TravelGroup.builder()
                .name("가입 대상 그룹")
                .inviteCode(inviteCode)
                .inviteExpiresAt(LocalDateTime.now().plusDays(7))
                .build());
    }

    private String joinBody(String inviteCode) {
        return "{\"inviteCode\": \"" + inviteCode + "\"}";
    }

    @Test
    @DisplayName("탈퇴 직후 같은 access token으로 그룹에 가입할 수 없다 — 토큰이 남아 있어도 401")
    void withdrawnUserCannotJoinGroupWithSurvivingToken() throws Exception {
        User user = seedUser();
        // 탈퇴 "전"에 발급된 토큰 — 탈퇴해도 서명·만료상으로는 여전히 유효하다
        String token = jwtProvider.createAccessToken(user.getId());
        TravelGroup group = seedGroup();

        userCommandService.withdraw(user.getId());

        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(joinBody(group.getInviteCode())))
                .andExpect(status().isUnauthorized());

        // 정원을 점유하지 않았는지까지 확인한다 — 401이어도 가입이 됐다면 의미가 없다
        assertThat(groupMemberRepository.countMembers(group.getId())).isZero();
    }

    @Test
    @DisplayName("탈퇴하지 않은 회원의 토큰은 그대로 동작한다 — 정상 흐름에 영향 없음")
    void activeUserIsUnaffected() throws Exception {
        User user = seedUser();
        String token = jwtProvider.createAccessToken(user.getId());
        TravelGroup group = seedGroup();

        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(joinBody(group.getInviteCode())))
                .andExpect(status().isOk());

        assertThat(groupMemberRepository.countMembers(group.getId())).isEqualTo(1);
    }
}
