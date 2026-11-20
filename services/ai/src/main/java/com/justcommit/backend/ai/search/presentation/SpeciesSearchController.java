package com.justcommit.backend.ai.search.presentation;

import com.justcommit.backend.ai.common.AiErrorCode;
import com.justcommit.backend.ai.search.application.SpeciesSearchService;
import com.justcommit.backend.common.exception.BusinessException;
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
            @RequestParam(defaultValue = "5") String size) {
        return ApiResponse.ok("문장으로 품종을 찾았습니다.",
                SpeciesSearchResponse.from(speciesSearchService.search(query, parseSize(size))));
    }

    // 숫자가 아닌 size도 공통 타입 변환 400이 아니라 INVALID_SEARCH_SIZE로 (범위 검증은 서비스)
    private static int parseSize(String size) {
        try {
            return Integer.parseInt(size.strip());
        } catch (NumberFormatException e) {
            throw new BusinessException(AiErrorCode.INVALID_SEARCH_SIZE);
        }
    }
}
