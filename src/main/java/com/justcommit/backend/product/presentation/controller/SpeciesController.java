package com.justcommit.backend.product.presentation.controller;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.product.application.SpeciesService;
import com.justcommit.backend.product.presentation.dto.SpeciesDetailResponse;
import com.justcommit.backend.product.presentation.dto.SpeciesSearchRequest;
import com.justcommit.backend.product.presentation.dto.SpeciesSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/species")
@RequiredArgsConstructor
public class SpeciesController {

    private final SpeciesService speciesService;

    @GetMapping
    public ApiResponse<PagingResponse<SpeciesSearchResponse>> searchSpecies(
            @ModelAttribute SpeciesSearchRequest request) {
        return ApiResponse.ok("품종 목록을 조회했습니다.",
                PagingResponse.from(speciesService.searchSpecies(request)));
    }

    @GetMapping("/{speciesId}")
    public ApiResponse<SpeciesDetailResponse> getSpeciesDetail(@PathVariable Long speciesId) {
        return ApiResponse.ok("품종 상세를 조회했습니다.", speciesService.getSpeciesDetail(speciesId));
    }
}
