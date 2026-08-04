package com.ssafy.ieumgil.domain.transit.controller;

import com.ssafy.ieumgil.domain.transit.dto.TransitResDTO;
import com.ssafy.ieumgil.domain.transit.service.PublicTransitQueryService;
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

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/api/transit")
@Tag(name = "대중교통 Controller")
public class TransitController {

    private final PublicTransitQueryService publicTransitQueryService;

    @GetMapping("/route")
    @Operation(summary = "대중교통 길찾기 소요시간/요금 조회",
            description = "현재 BUS/SUBWAY만 지원한다. WALK/TAXI/CAR는 domain.place 연동 후 추가 예정.")
    public CustomResponse<TransitResDTO.Route> getRoute(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double sy,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double sx,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double ey,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double ex,
            @RequestParam @NotBlank String mode) {
        return CustomResponse.onSuccess(
                publicTransitQueryService.getRoute(sy, sx, ey, ex, mode));
    }
}
