package com.ssafy.ieumgil.domain.transit.controller;

import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.exception.TransitErrorCode;
import com.ssafy.ieumgil.domain.transit.exception.TransitException;
import com.ssafy.ieumgil.domain.transit.service.PublicTransitQueryService;
import com.ssafy.ieumgil.global.security.ActiveUserChecker;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// PlaceControllerTest와 동일한 이유로 필터 체인을 끈다 — JwtAuthenticationFilter가
// 이 슬라이스에 없는 JwtProvider를 요구한다.
@WebMvcTest(TransitController.class)
@AutoConfigureMockMvc(addFilters = false)
class TransitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicTransitQueryService publicTransitQueryService;

    @MockitoBean
    private JwtProvider jwtProvider;
    // 필터가 계정 생존 확인(탈퇴 차단)까지 하므로 이 빈도 슬라이스에 없다
    @MockitoBean
    private ActiveUserChecker activeUserChecker;

    @Test
    void getRouteReturnsOkWithResult() throws Exception {
        when(publicTransitQueryService.getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("BUS")))
                .thenReturn(TransitResDTO.Route.builder()
                        .durationMin(42).fare(1400).intervalMin(13).estimated(false)
                        .fareConfidence(TransitResDTO.FareConfidence.CONFIRMED)
                        .build());

        mockMvc.perform(get("/api/transit/route")
                        .param("sy", "37.4979").param("sx", "127.0276")
                        .param("ey", "37.5665").param("ex", "127.1054")
                        .param("mode", "BUS")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result.durationMin").value(42))
                .andExpect(jsonPath("$.result.fare").value(1400))
                .andExpect(jsonPath("$.result.intervalMin").value(13));
    }

    @Test
    void getRouteWithNoRouteFoundReturns404() throws Exception {
        when(publicTransitQueryService.getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("BUS")))
                .thenThrow(new TransitException(TransitErrorCode.ROUTE_NOT_FOUND));

        mockMvc.perform(get("/api/transit/route")
                        .param("sy", "37.4979").param("sx", "127.0276")
                        .param("ey", "37.5665").param("ex", "127.1054")
                        .param("mode", "BUS")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRouteWithMissingModeReturns400() throws Exception {
        mockMvc.perform(get("/api/transit/route")
                        .param("sy", "37.4979").param("sx", "127.0276")
                        .param("ey", "37.5665").param("ex", "127.1054")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRouteWithOutOfRangeCoordinateReturns400() throws Exception {
        mockMvc.perform(get("/api/transit/route")
                        .param("sy", "999").param("sx", "127.0276")
                        .param("ey", "37.5665").param("ex", "127.1054")
                        .param("mode", "BUS")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRouteWithUnsupportedModeReturns400() throws Exception {
        when(publicTransitQueryService.getRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("WALK")))
                .thenThrow(new TransitException(TransitErrorCode.UNSUPPORTED_MODE));

        mockMvc.perform(get("/api/transit/route")
                        .param("sy", "37.4979").param("sx", "127.0276")
                        .param("ey", "37.5665").param("ex", "127.1054")
                        .param("mode", "WALK")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isBadRequest());
    }

    private UsernamePasswordAuthenticationToken memberAuthentication(Long memberId) {
        return new UsernamePasswordAuthenticationToken(
                memberId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
