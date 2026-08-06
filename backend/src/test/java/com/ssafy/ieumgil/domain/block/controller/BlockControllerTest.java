package com.ssafy.ieumgil.domain.block.controller;

import com.ssafy.ieumgil.domain.block.service.BlockCommandService;
import com.ssafy.ieumgil.domain.block.service.DetailLockService;
import com.ssafy.ieumgil.global.security.ActiveUserChecker;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// TransitCandidateControllerTest와 동일한 이유로 필터 체인을 끈다 — JwtAuthenticationFilter가
// 이 슬라이스에 없는 JwtProvider를 요구한다. 여기서 보는 건 인가가 아니라 요청 바디 검증이다.
@WebMvcTest(BlockController.class)
@AutoConfigureMockMvc(addFilters = false)
class BlockControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BlockCommandService blockCommandService;

    @MockitoBean
    private DetailLockService detailLockService;

    @MockitoBean
    private JwtProvider jwtProvider;
    // 필터가 계정 생존 확인(탈퇴 차단)까지 하므로 이 빈도 슬라이스에 없다
    @MockitoBean
    private ActiveUserChecker activeUserChecker;

    @Test
    @DisplayName("생성 시 음수 오프셋은 400이다 — DB 제약까지 가서 500이 되면 안 된다")
    void rejectsNegativeOffsetOnCreate() throws Exception {
        mockMvc.perform(post("/api/projects/1/blocks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"category":"ETC","name":"불가","startOffsetMinutes":-5,"source":"MANUAL"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_1"))
                .andExpect(jsonPath("$.result.startOffsetMinutes").value("시작 오프셋은 0 이상이어야 합니다."));

        then(blockCommandService).should(never()).createBlock(any(), any(), any(), any());
    }

    @Test
    @DisplayName("이동 시 음수 오프셋은 400이다")
    void rejectsNegativeOffsetOnMove() throws Exception {
        mockMvc.perform(patch("/api/blocks/1/position")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startOffsetMinutes":-1,"orderKey":"a0"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_1"))
                .andExpect(jsonPath("$.result.startOffsetMinutes").value("시작 오프셋은 0 이상이어야 합니다."));

        then(blockCommandService).should(never()).move(any(), any(), any(), any());
    }
}
