package com.justcommit.backend.product.search.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.product.search.application.ProductIndexService.ReindexResult;
import com.justcommit.backend.product.search.application.SearchIndexJobService;
import com.justcommit.backend.product.search.application.SearchIndexJobStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 상품 검색 인덱스 관리 (ADMIN만, SecurityConfig의 /api/v1/admin/**)
// - 전체 색인: 처음 띄울 때 · DLT로 빠진 색인을 맞출 때
// - 무중단 재색인: 매핑 · 분석기나 별칭 · 학명이 바뀌었을 때 (#487), 비동기 실행 · 진행 상태 조회 (#563)
@RestController
@Tag(name = "admin-product-search-index-controller", description = "관리자 상품 검색 인덱스 API")
@RequestMapping("/api/v1/admin/products/searchIndex")
@RequiredArgsConstructor
public class AdminProductSearchIndexController {

    private static final String JOBS_PATH = "/api/v1/admin/products/searchIndex/jobs/";

    private final SearchIndexJobService jobService;

    @Operation(summary = "상품 전체 색인",
            description = "DB의 모든 상품을 OpenSearch alias products에 다시 씁니다. 삭제(DELETED) 상품은 문서를 지우고, "
                    + "나머지 상태 · 숨김 상품은 상태와 함께 저장합니다(검색할 때 거름). 여러 번 실행해도 결과가 같고, "
                    + "skipped는 같거나 더 새 버전이 이미 있어 건너뛴 수, failed가 0이 아니면 로그를 확인하고 다시 실행합니다. "
                    + "끝날 때까지 기다렸다 응답하며, 작업 기록(GET jobs)에도 REINDEX_ALL로 남습니다. "
                    + "OpenSearch에 연결할 수 없거나 인덱스(alias)가 아직 없으면 500, 어느 서버에서든 전체 색인 · 재색인이 진행 중이면 "
                    + "409 SEARCH_INDEXING_IN_PROGRESS입니다.")
    @PostMapping
    public ApiResponse<ReindexResult> reindexAll() {
        return ApiResponse.ok("상품 전체 색인을 마쳤습니다.", jobService.reindexAll());
    }

    @Operation(summary = "상품 검색 무중단 재색인 요청",
            description = "작업을 등록하고 바로 202와 작업(jobId, phase=QUEUED)을 돌려줍니다. Location의 GET jobs/{jobId}로 "
                    + "진행 상태를 확인합니다. 재색인은 별도 실행자에서 지금 매핑으로 새 인덱스(products_v{n+1})를 채우고(FILLING) "
                    + "refresh 뒤 alias products를 한 번에 옮긴 다음(SWITCHING_ALIAS), 그사이 바뀐 상품을 맞추고(CATCHING_UP) "
                    + "옛 인덱스를 지웁니다(CLEANING_UP → COMPLETED). 그동안 검색은 옛 인덱스로 계속 응답합니다. "
                    + "새 인덱스를 채우다 실패하면 새 인덱스를 지우고 FAILED이며 alias는 그대로입니다. 따라잡기에 실패한 건이 있으면 "
                    + "옛 인덱스를 남기고 previousDeleted=false로 끝납니다(전체 색인을 다시 실행해 맞춘 뒤 옛 인덱스를 직접 지움). "
                    + "어느 서버에서든 전체 색인 · 재색인이 진행 중이면 409 SEARCH_INDEXING_IN_PROGRESS입니다.")
    @PostMapping("/rebuilds")
    public ResponseEntity<ApiResponse<SearchIndexJobStatus>> rebuild() {
        SearchIndexJobStatus job = jobService.requestRebuild();
        return ResponseEntity.accepted().location(URI.create(JOBS_PATH + job.jobId()))
                .body(new ApiResponse<>(HttpStatus.ACCEPTED.value(), null,
                        "상품 검색 무중단 재색인을 등록했습니다. 진행 상태는 작업 조회로 확인합니다.", job));
    }

    @Operation(summary = "상품 검색 색인 작업 조회",
            description = "전체 색인(REINDEX_ALL) · 무중단 재색인(REBUILD) 작업 하나의 진행 상태입니다. "
                    + "phase는 QUEUED · FILLING · SWITCHING_ALIAS · CATCHING_UP · CLEANING_UP · COMPLETED · FAILED, "
                    + "processed · skipped · failed는 채우기 단계, caughtUp은 따라잡기 단계 결과(재색인만), "
                    + "진행률은 processed / totalCount(시작할 때 상품 수)로 어림합니다. FAILED면 failedPhase · failureReason을 봅니다. "
                    + "실행 서버가 죽어 heartbeatAt이 2분 넘게 멈춘 작업은 FAILED로 바뀝니다. 없으면 404 SEARCH_INDEX_JOB_NOT_FOUND입니다.")
    @GetMapping("/jobs/{jobId}")
    public ApiResponse<SearchIndexJobStatus> job(@PathVariable Long jobId) {
        return ApiResponse.ok("상품 검색 색인 작업을 조회했습니다.", jobService.status(jobId));
    }

    @Operation(summary = "최근 상품 검색 색인 작업 목록",
            description = "최근 작업 10개(최신순)입니다. 409를 받았을 때 진행 중인 작업(finishedAt이 없는 것)을 찾을 때 씁니다.")
    @GetMapping("/jobs")
    public ApiResponse<List<SearchIndexJobStatus>> jobs() {
        return ApiResponse.ok("최근 상품 검색 색인 작업을 조회했습니다.", jobService.recent());
    }
}
