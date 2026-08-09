package com.ssafy.ieumgil.domain.group.aop;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인가 계층(@GroupMember AOP) — HTTP 경계에서의 회귀 테스트.
 *
 * 핵심 규약은 판정 순서다: 존재 확인(404) 먼저, 멤버십(403) 나중 —
 * 순서가 뒤집히면 비멤버가 404/403의 차이로 자원의 존재 여부를 알아낼 수 있고,
 * 없는 자원에 403이 나가 프론트가 권한 문제로 오해한다.
 */
@AutoConfigureMockMvc
class GroupMemberAuthorizationTest extends IntegrationTestSupport {

    private static final String FIELDS_BODY = """
            {"fields": [{"field": "budget", "value": 1000}]}
            """;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    BlockRepository blockRepository;

    User member;
    User outsider;
    long blockId;

    @BeforeEach
    void seed() {
        member = seedUser();
        outsider = seedUser();   // 어느 그룹에도 속하지 않는 사용자
        Project project = seedProject(member);
        blockId = blockRepository.save(Block.builder()
                .name("인가 대상")
                .category(BlockCategory.ETC)
                .orderKey("a0")
                .source(BlockSource.MANUAL)
                .project(project)
                .author(member)
                .build()).getId();
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    @Test
    @DisplayName("토큰 없이 블록을 수정하면 401")
    void withoutTokenReturns401() throws Exception {
        mockMvc.perform(patch("/api/blocks/{blockId}/fields", blockId)
                        .contentType(MediaType.APPLICATION_JSON).content(FIELDS_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("비멤버가 남의 그룹 블록을 수정하면 403(GROUP403)")
    void outsiderReturns403() throws Exception {
        mockMvc.perform(patch("/api/blocks/{blockId}/fields", blockId)
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content(FIELDS_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP403"));
    }

    @Test
    @DisplayName("존재하지 않는 블록은 비멤버에게도 404 — 403보다 404가 먼저다")
    void unknownBlockReturns404EvenForOutsider() throws Exception {
        // 404가 먼저여야 하는 이유: 여기서 403이 나가면 "404가 아니네? 그 블록은 존재하는구나"를
        // 비멤버가 추론할 수 있다. 존재 여부 자체를 숨기는 것이 규약이다.
        mockMvc.perform(patch("/api/blocks/{blockId}/fields", 9_999_999L)
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON).content(FIELDS_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BLOCK404"));
    }

    @Test
    @DisplayName("멤버는 정상적으로 수정할 수 있다 — 인가가 과하게 막지 않는지")
    void memberCanUpdate() throws Exception {
        mockMvc.perform(patch("/api/blocks/{blockId}/fields", blockId)
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON).content(FIELDS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.applied.budget").value(true));
    }
}
