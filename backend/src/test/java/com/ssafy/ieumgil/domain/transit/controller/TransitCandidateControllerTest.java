package com.ssafy.ieumgil.domain.transit.controller;

import com.ssafy.ieumgil.domain.transit.dto.TransitCandidateResDTO;
import com.ssafy.ieumgil.domain.transit.service.TransitCandidateService;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// TransitControllerTest/PlaceControllerTest와 동일한 이유로 필터 체인을 끈다 — JwtAuthenticationFilter가
// 이 슬라이스에 없는 JwtProvider를 요구한다. 인가(@GroupMember) 통합 테스트는 Task 9다.
@WebMvcTest(TransitCandidateController.class)
@AutoConfigureMockMvc(addFilters = false)
class TransitCandidateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransitCandidateService transitCandidateService;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("후보 계산 결과를 CustomResponse로 감싸 반환한다")
    void returnsCandidatesWrappedInCustomResponse() throws Exception {
        given(transitCandidateService.calculate(eq(1L), anyList(), any()))
                .willReturn(TransitCandidateResDTO.Result.builder().segments(List.of()).build());

        mockMvc.perform(post("/api/projects/1/transit-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockIds\":[101,105]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.code").value("COMMON200"))
                .andExpect(jsonPath("$.result.segments").isArray());
    }

    @Test
    @DisplayName("dayStart를 HH:mm으로 받아 그대로 서비스에 넘긴다")
    void passesDayStartToService() throws Exception {
        given(transitCandidateService.calculate(eq(1L), anyList(), eq(LocalTime.of(9, 30))))
                .willReturn(TransitCandidateResDTO.Result.builder().segments(List.of()).build());

        mockMvc.perform(post("/api/projects/1/transit-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockIds\":[101,105],\"dayStart\":\"09:30\"}"))
                .andExpect(status().isOk());

        verify(transitCandidateService).calculate(eq(1L), anyList(), eq(LocalTime.of(9, 30)));
    }

    @Test
    @DisplayName("blockIds가 31개면 400이다")
    void rejectsTooManyBlockIds() throws Exception {
        String ids = IntStream.rangeClosed(1, 31).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/api/projects/1/transit-candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockIds\":[" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON400_1"))
                .andExpect(jsonPath("$.result.blockIds").value("한 번에 30개까지만 계산할 수 있습니다."));
    }
}
