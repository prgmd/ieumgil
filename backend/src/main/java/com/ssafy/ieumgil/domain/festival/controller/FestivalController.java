package com.ssafy.ieumgil.domain.festival.controller;

import com.ssafy.ieumgil.domain.festival.dto.FestivalResDTO;
import com.ssafy.ieumgil.domain.festival.service.FestivalQueryService;
import com.ssafy.ieumgil.global.apiPayload.CustomResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/festivals")
@Tag(name = "축제 Controller")
public class FestivalController {

    private final FestivalQueryService festivalQueryService;

    @GetMapping("/{contentId}/homepage")
    @Operation(summary = "축제 공식 홈페이지 조회", description = "배치가 미리 채운 Festival.homepage를 반환합니다. 없으면 url=null.")
    public CustomResponse<FestivalResDTO.Homepage> getHomepage(@PathVariable String contentId) {
        return CustomResponse.onSuccess(new FestivalResDTO.Homepage(festivalQueryService.getHomepageUrl(contentId)));
    }
}
