package com.ssafy.ieumgil.domain.transit;

import com.ssafy.ieumgil.domain.block.entity.Block;
import com.ssafy.ieumgil.domain.block.entity.BlockCategory;
import com.ssafy.ieumgil.domain.block.entity.BlockSource;
import com.ssafy.ieumgil.domain.block.repository.BlockRepository;
import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.domain.project.entity.Project;
import com.ssafy.ieumgil.domain.transit.service.PublicTransitQueryService;
import com.ssafy.ieumgil.domain.user.entity.User;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import com.ssafy.ieumgil.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TransitCandidateController에 붙은 {@code @GroupMember(PROJECT_ID)} 회귀 테스트.
 * {@link com.ssafy.ieumgil.domain.group.aop.GroupMemberAuthorizationTest}와 같은 패턴 —
 * 존재 확인(404) 먼저, 멤버십(403) 나중이 여기서도 지켜지는지 확인한다.
 *
 * <p>추가로 이 엔드포인트 고유의 두 번째 방어선도 검증한다: 컨트롤러 인가는 "이 사용자가
 * 이 프로젝트 멤버인가"까지만 보고, "이 블록이 그 프로젝트 것인가"는 서비스가 본다
 * (blockIdsFromAnotherProjectAreRejected). 남의 프로젝트 블록 id를 섞어 좌표를 알아내는
 * 경로를 막는 지점이라 인가 테스트에 함께 둔다.
 *
 * <p>PlaceQueryService/PublicTransitQueryService는 mock — 인가 계층만 검증 대상이고,
 * 실제로 통과하면 카카오/ODsay 외부 API 호출까지 이어지므로 테스트에서는 그 지점을 끊는다.
 */
@AutoConfigureMockMvc
class TransitCandidateAuthorizationTest extends IntegrationTestSupport {

    /** 서울시청 근방 두 점 — 직선거리 300m 미만이라 도보만 후보로 잡힌다(TransitCandidateServiceImpl 참고) */
    private static final BigDecimal LAT_A = BigDecimal.valueOf(37.5666);
    private static final BigDecimal LNG_A = BigDecimal.valueOf(126.9784);
    private static final BigDecimal LAT_B = BigDecimal.valueOf(37.5680);
    private static final BigDecimal LNG_B = BigDecimal.valueOf(126.9784);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JwtProvider jwtProvider;
    @Autowired
    BlockRepository blockRepository;
    @MockitoBean
    PlaceQueryService placeQueryService;
    @MockitoBean
    PublicTransitQueryService publicTransitQueryService;

    User member;
    User outsider;
    long projectId;
    long blockAId;
    long blockBId;
    long otherProjectBlockId;

    @BeforeEach
    void seed() {
        member = seedUser();
        outsider = seedUser();
        Project project = seedProject(member);
        projectId = project.getId();
        blockAId = seedBlock(project, LAT_A, LNG_A).getId();
        blockBId = seedBlock(project, LAT_B, LNG_B).getId();

        Project otherProject = seedProject(seedUser());
        otherProjectBlockId = seedBlock(otherProject, LAT_A, LNG_A).getId();
    }

    private Block seedBlock(Project project, BigDecimal lat, BigDecimal lng) {
        return blockRepository.save(Block.builder()
                .name("인가 대상")
                .category(BlockCategory.SPOT)
                .orderKey("a0")
                .source(BlockSource.MANUAL)
                .project(project)
                .author(member)
                .lat(lat)
                .lng(lng)
                .build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtProvider.createAccessToken(user.getId());
    }

    private String blockIdsBody(long... ids) {
        StringBuilder sb = new StringBuilder("{\"blockIds\": [");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ids[i]);
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("토큰이 없으면 401이다")
    void unauthenticatedIsRejected() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/transit-candidates", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockIdsBody(blockAId, blockBId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("그룹 멤버가 아니면 403이다")
    void nonMemberIsForbidden() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/transit-candidates", projectId)
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockIdsBody(blockAId, blockBId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP403"));
    }

    @Test
    @DisplayName("존재하지 않는 프로젝트는 비멤버에게도 404 — 403보다 404가 먼저다")
    void unknownProjectReturns404EvenForOutsider() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/transit-candidates", 9_999_999L)
                        .header("Authorization", bearer(outsider))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockIdsBody(blockAId, blockBId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT404"));
    }

    @Test
    @DisplayName("멤버는 통과한다")
    void memberIsAllowed() throws Exception {
        given(placeQueryService.getWalkingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .willReturn(Optional.of(new PlaceResDTO.WalkingRoute(150, 3)));

        mockMvc.perform(post("/api/projects/{projectId}/transit-candidates", projectId)
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockIdsBody(blockAId, blockBId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.segments[0].fromBlockId").value(blockAId))
                .andExpect(jsonPath("$.result.segments[0].toBlockId").value(blockBId))
                // 실 DB에서 조립돼 실제로 직렬화되는 값까지 확인한다 — 300m 미만이라 WALK만 후보다.
                .andExpect(jsonPath("$.result.segments[0].defaultMode").value("WALK"))
                .andExpect(jsonPath("$.result.segments[0].candidates[0].durationMin").value(3));
    }

    @Test
    @DisplayName("멤버라도 다른 프로젝트의 블록 id를 섞으면 400이다 — 좌표를 알아내는 경로를 막는다")
    void blockIdsFromAnotherProjectAreRejected() throws Exception {
        mockMvc.perform(post("/api/projects/{projectId}/transit-candidates", projectId)
                        .header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(blockIdsBody(blockAId, otherProjectBlockId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TRANSIT400_1"));
    }
}
