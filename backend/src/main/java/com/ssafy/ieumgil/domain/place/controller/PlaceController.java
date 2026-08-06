package com.ssafy.ieumgil.domain.place.controller;

import com.ssafy.ieumgil.domain.place.dto.PlaceResDTO;
import com.ssafy.ieumgil.domain.place.service.PlaceQueryService;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/places")
@Tag(name = "장소 Controller")
public class PlaceController {

    private final PlaceQueryService placeQueryService;

    @GetMapping
    @Operation(summary = "장소 키워드 검색", description = "카카오 로컬 keyword 검색 상위 15건을 반환합니다.")
    public CustomResponse<List<PlaceResDTO.Place>> searchPlaces(
            @RequestParam @NotBlank String query,
            @RequestParam(required = false) @DecimalMin("-90") @DecimalMax("90") Double lat,
            @RequestParam(required = false) @DecimalMin("-180") @DecimalMax("180") Double lng) {
        return CustomResponse.onSuccess(placeQueryService.searchPlaces(query, lat, lng));
    }

    @GetMapping("/address")
    @Operation(summary = "좌표→주소 변환", description = "coord2address 역지오코딩 결과를 반환합니다.")
    public CustomResponse<PlaceResDTO.Address> reverseGeocode(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        return CustomResponse.onSuccess(placeQueryService.reverseGeocode(lat, lng).orElse(null));
    }
}
