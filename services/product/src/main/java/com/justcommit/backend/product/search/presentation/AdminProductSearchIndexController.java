package com.justcommit.backend.product.search.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.product.search.application.ProductIndexService;
import com.justcommit.backend.product.search.application.ProductIndexService.RebuildResult;
import com.justcommit.backend.product.search.application.ProductIndexService.ReindexResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 상품 검색 인덱스 관리 (ADMIN만, SecurityConfig의 /api/v1/admin/**)
// - 전체 색인: 처음 띄울 때 · DLT로 빠진 색인을 맞출 때
// - 무중단 재색인: 매핑 · 분석기나 별칭 · 학명이 바뀌었을 때 (#487)
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

    @Operation(summary = "상품 검색 무중단 재색인",
            description = "지금 매핑으로 새 인덱스(products_v{n+1})를 만들어 DB 전체를 채운 뒤 alias products를 한 번에 옮기고 "
                    + "옛 인덱스를 지웁니다. 그동안 검색은 옛 인덱스로 계속 응답합니다. 매핑 · 분석기를 바꿔 배포했거나 "
                    + "별칭 · 학명 데이터가 바뀌었을 때 씁니다. 새 인덱스를 채우다 실패하면 새 인덱스를 지우고 500이며 alias는 그대로입니다. "
                    + "이미 재색인 중이면 409 SEARCH_REBUILD_IN_PROGRESS입니다.")
    @PostMapping("/rebuild")
    public ApiResponse<RebuildResult> rebuild() {
        return ApiResponse.ok("상품 검색 인덱스를 새로 만들고 alias를 옮겼습니다.", productIndexService.rebuild());
    }
}
