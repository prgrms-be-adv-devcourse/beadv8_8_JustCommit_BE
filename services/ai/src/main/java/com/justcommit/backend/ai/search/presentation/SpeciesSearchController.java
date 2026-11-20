package com.justcommit.backend.ai.search.presentation;

import com.justcommit.backend.ai.search.application.SpeciesSearchService;
import com.justcommit.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 품종 의미 검색 외부 API (공개, Gateway가 IP 기준 요청 수 제한). 카드 정보는 화면이 상품 ID로 product에서 조회한다
@RestController
@RequestMapping("/api/v1/speciesSearches")
@RequiredArgsConstructor
public class SpeciesSearchController {

    private final SpeciesSearchService speciesSearchService;

    @GetMapping
    public ApiResponse<SpeciesSearchResponse> search(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "5") int size) {
        return ApiResponse.ok("문장으로 품종을 찾았습니다.",
                SpeciesSearchResponse.from(speciesSearchService.search(query, size)));
    }
}
