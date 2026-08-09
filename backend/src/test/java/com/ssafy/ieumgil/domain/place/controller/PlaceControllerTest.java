package com.ssafy.ieumgil.domain.place.controller;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.global.security.ActiveUserChecker;
import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ChatbotControllerTest와 동일한 이유로 필터 체인을 끈다 — JwtAuthenticationFilter가
// 이 슬라이스에 없는 JwtProvider를 요구한다.
@WebMvcTest(PlaceController.class)
@AutoConfigureMockMvc(addFilters = false)
class PlaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlaceQueryService placeQueryService;

    @MockitoBean
    private JwtProvider jwtProvider;
    // 필터가 계정 생존 확인(탈퇴 차단)까지 하므로 이 빈도 슬라이스에 없다
    @MockitoBean
    private ActiveUserChecker activeUserChecker;

    @Test
    void searchPlacesReturnsOkWithResults() throws Exception {
        when(placeQueryService.searchPlaces(eq("성산일출봉"), any(), any()))
                .thenReturn(List.of(PlaceResDTO.Place.builder()
                        .placeId("26338954").name("성산일출봉").address("제주 서귀포시 성산읍")
                        .lat(33.4581).lng(126.9425).category("관광명소").build()));

        mockMvc.perform(get("/api/places")
                        .param("query", "성산일출봉")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isSuccess").value(true))
                .andExpect(jsonPath("$.result[0].name").value("성산일출봉"));
    }

    @Test
    void searchPlacesWithoutQueryReturns400() throws Exception {
        mockMvc.perform(get("/api/places")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchPlacesWithOutOfRangeLatReturns400() throws Exception {
        mockMvc.perform(get("/api/places")
                        .param("query", "카페")
                        .param("lat", "999")
                        .param("lng", "126.5")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reverseGeocodeReturnsOkWithAddress() throws Exception {
        when(placeQueryService.reverseGeocode(33.4581, 126.9425))
                .thenReturn(Optional.of(new PlaceResDTO.Address(
                        "제주 서귀포시 성산읍 성산리", "제주 서귀포시 성산읍 일출로 284-12")));

        mockMvc.perform(get("/api/places/address")
                        .param("lat", "33.4581")
                        .param("lng", "126.9425")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.address").value("제주 서귀포시 성산읍 성산리"))
                .andExpect(jsonPath("$.result.roadAddress").value("제주 서귀포시 성산읍 일출로 284-12"));
    }

    @Test
    void reverseGeocodeWithNonNumericLatReturns400() throws Exception {
        mockMvc.perform(get("/api/places/address")
                        .param("lat", "abc")
                        .param("lng", "126.9425")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reverseGeocodeWithNoMatchReturnsOkWithNullResult() throws Exception {
        // 유효한 좌표 범위 안이지만(대한민국 근해) 매칭 주소가 없는 경우 — 400이 아니라 200 + null이 정답.
        when(placeQueryService.reverseGeocode(33.0, 124.0)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/places/address")
                        .param("lat", "33.0")
                        .param("lng", "124.0")
                        .with(authentication(memberAuthentication(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("주소→좌표: 결과가 있으면 좌표와 두 주소를 준다")
    void 지오코딩_성공() throws Exception {
        when(placeQueryService.geocodeAddress(eq("서울시청")))
                .thenReturn(Optional.of(new PlaceResDTO.Geocode(
                        37.5663, 126.9779, "서울 중구 세종대로 110", "서울 중구 태평로1가 31")));

        mockMvc.perform(get("/api/places/geocode").param("address", "서울시청"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.lat").value(37.5663))
                .andExpect(jsonPath("$.result.roadAddress").value("서울 중구 세종대로 110"));
    }

    @Test
    @DisplayName("주소→좌표: 결과가 없으면 404다 — 200에 null 을 주지 않는다")
    void 지오코딩_결과_없으면_404다() throws Exception {
        when(placeQueryService.geocodeAddress(eq("없는주소"))).thenReturn(Optional.empty());

        // 200 + result:null 로 주면 프론트가 "성공했는데 값이 없다"를 매번 분기해야 한다
        mockMvc.perform(get("/api/places/geocode").param("address", "없는주소"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PLACE404"));
    }

    private UsernamePasswordAuthenticationToken memberAuthentication(Long memberId) {
        return new UsernamePasswordAuthenticationToken(
                memberId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
