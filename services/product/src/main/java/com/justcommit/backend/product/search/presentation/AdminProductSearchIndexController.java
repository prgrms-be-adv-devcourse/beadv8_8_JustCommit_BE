package com.justcommit.backend.product.search.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.product.search.application.ProductIndexService;
import com.justcommit.backend.product.search.application.ProductIndexService.ReindexResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 상품 전체 색인 (ADMIN만, SecurityConfig의 /api/v1/admin/**). 처음 띄울 때 · 인덱스를 다시 만들 때 · DLT로 빠진 색인을 맞출 때
@RestController
@Tag(name = "admin-product-search-index-controller", description = "관리자 상품 검색 인덱스 API")
@RequestMapping("/api/v1/admin/products/searchIndex")
@RequiredArgsConstructor
public class AdminProductSearchIndexController {

    private final ProductIndexService productIndexService;

    @Operation(summary = "상품 전체 색인",
            description = "DB의 모든 상품을 OpenSearch alias products에 다시 씁니다. 삭제(DELETED) 상품은 문서를 지우고, "
                    + "나머지 상태 · 숨김 상품은 상태와 함께 저장합니다(검색할 때 거름). 여러 번 실행해도 결과가 같고, "
                    + "skipped는 같거나 더 새 버전이 이미 있어 건너뛴 수, failed가 0이 아니면 로그를 확인하고 다시 실행합니다. "
                    + "OpenSearch에 연결할 수 없거나 인덱스(alias)가 아직 없으면 500입니다.")
    @PostMapping
    public ApiResponse<ReindexResult> reindexAll() {
        return ApiResponse.ok("상품 전체 색인을 마쳤습니다.", productIndexService.reindexAll());
    }
}
