package com.justcommit.backend.ai.admin.presentation.internal;

import com.justcommit.backend.ai.product.application.ProductReindexService;
import com.justcommit.backend.ai.species.application.SpeciesReindexService;
import com.justcommit.backend.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// ai 운영 API (내부 키 필요, Gateway가 라우팅하지 않음). 규칙 → docs/reference/internal-apis.md
@RestController
@RequestMapping("/internal/v1/ai/admin")
@RequiredArgsConstructor
public class AdminInternalController {

    private final SpeciesReindexService speciesReindexService;
    private final ProductReindexService productReindexService;

    // 재임베딩: 문서가 바뀐 것만 임베딩하고, 다시 호출해도 결과가 같다
    @PostMapping("/reindex")
    public ApiResponse<?> reindex(@RequestParam ReindexTarget target) {
        return switch (target) {
            case SPECIES -> ApiResponse.ok("품종을 재임베딩했습니다.", speciesReindexService.reindex());
            case PRODUCT -> ApiResponse.ok("상품을 재임베딩했습니다.", productReindexService.reindex());
        };
    }
}
