package com.justcommit.backend.product.product.presentation;


import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.product.image.application.ProductImageService;
import com.justcommit.backend.product.image.presentation.dto.ImageUploadUrlRequest;
import com.justcommit.backend.product.image.presentation.dto.ImageUploadUrlResponse;
import com.justcommit.backend.product.product.application.ProductService;
import com.justcommit.backend.product.product.presentation.dto.*;
import com.justcommit.backend.product.search.application.ProductSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Tag(name = "product-controller", description = "판매글 등록 및 조회 API")
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductImageService productImageService;
    private final ProductService productService;
    private final ProductSearchService productSearchService;

    @Operation(summary = "상품 등록", description = "로그인한 회원을 판매자로 상품을 등록합니다. 금지 품종은 등록할 수 없습니다. "
            + "사진(pictureUrls)은 1장 이상 10장 이하로 보내야 합니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductDetailResponse> createProduct(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestBody @Valid  ProductRequest request) {

        return ApiResponse.created("상품이 성공적으로 등록되었습니다.",
                productService.createProduct(authMember.memberId(), request));
    }

    @Operation(summary = "상품 목록 조회", description = "검수 중 · 반려 · 삭제 · 숨김 상품은 항상 제외하고 판매 중 · 결제 대기 · 판매 완료 상품을 보여 줍니다. "
            + "keyword가 있으면 OpenSearch로 제목 > 품종명 · 별칭 · 학명 순 가중치로 찾고(띄어쓰기 · 기호 · 대소문자 무시, 설명은 찾지 않음), 기본 정렬은 관련도순(relevance)입니다. "
            + "keyword가 없으면 DB에서 조회하고 기본 정렬은 최신순(latest)입니다. sort: relevance · latest · priceAsc · priceDesc. "
            + "필터: 품종, 카테고리, 광량 · 물주기 · 난이도(품종 기준), 가격 범위, onSale=true(판매 중만). "
            + "판매자 상점: sellerId(그 판매자 상품만), status(ACTIVE · PAYMENT_PENDING · SOLD_OUT 중 하나, 그 밖이면 400 INVALID_SEARCH_STATUS). "
            + "판매 완료 개수는 sellerId + status=SOLD_OUT 응답의 totalElements입니다. "
            + "OpenSearch 장애 때는 DB 검색(제목 · 품종명 포함 여부, 최신순)으로 대신 응답합니다. 페이지 크기 기본 20, 최대 50.")
    @GetMapping
    public ApiResponse<PagingResponse<ProductSearchResponse>> searchProducts (
            @ModelAttribute ProductSearchRequest request) {

        return ApiResponse.ok("상품 목록을 조회했습니다.",
                PagingResponse.from(productSearchService.search(request)));
    }

    @Operation(summary = "상품 상세 조회", description = "품종 정보와 사진 목록을 포함합니다. 숨긴 상품은 판매자 본인만 조회할 수 있고, 다른 사람이 조회하면 403 PRODUCT_HIDDEN입니다. "
            + "없거나 삭제된 상품은 404 PRODUCT_NOT_FOUND입니다.")
    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> getProductDetail(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId) {

        Long memberId = authMember == null ? null : authMember.memberId();
        return ApiResponse.ok("상품 상세를 조회했습니다.",
                productService.getProductDetail(productId, memberId));
    }

    @Operation(summary="상품 수정", description  ="판매 중인 본인 상품만 수정할 수 있습니다. "
            + "사진(pictureUrls)은 1장 이상 10장 이하로 보내야 하며, 보낸 목록으로 통째로 바뀝니다.")
    @PutMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> updateProduct(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId,
            @RequestBody @Valid ProductRequest request){

        return ApiResponse.ok("상품을 수정했습니다", productService.updateProduct(authMember.memberId(), productId, request));
    }

    @Operation(summary="상품 삭제", description  ="판매 중인 본인 상품만 삭제할 수 있습니다.")
    @DeleteMapping("/{productId}")
    public ApiResponse<Void> deleteProduct(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId){

        productService.deleteProduct(authMember.memberId(), productId);
        return ApiResponse.ok("상품을 삭제했습니다.");
    }

    @Operation(summary="상품 숨김 설정", description  ="삭제되지 않은 본인 상품을 숨기거나 다시 노출합니다. 결제 대기 중인 상품도 숨길 수 있습니다.")
    @PatchMapping("/{productId}/hidden")
    public ApiResponse<Void> changeHidden(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId  ,
            @RequestBody @Valid ProductHiddenRequest request){

        productService.changeHidden(authMember.memberId(), productId, request.hidden());
        return ApiResponse.ok(request.hidden() ? "상품을 숨겼습니다." : "상품 숨김을 해제했습니다.");
    }


    @Operation(summary = "내 판매 물품 조회", description = "로그인한 판매자가 등록한 상품을 최신순으로 조회합니다. 삭제된 상품은 제외합니다. "
            + "status로 상태를, hidden으로 숨김 여부를 거를 수 있습니다 (hidden 생략 시 숨긴 상품 포함 전체).")
    @GetMapping("/me")
    public ApiResponse<PagingResponse<MyProductResponse>> getMyProducts(
            @AuthenticationPrincipal AuthMember authMember,
            @ModelAttribute MyProductSearchRequest request){

        return ApiResponse.ok("내 판매 목록을 조회했습니다", PagingResponse.from(productService.getMyProducts(authMember.memberId(), request)));
    }

    @Operation(summary = "상품 이미지 업로드 링크 발급",
            description = "판매자만 발급받을 수 있습니다. 응답의 uploadUrl로 파일을 PUT 업로드하고, "
                    + "imageUrl을 상품 등록·수정의 pictureUrls에 넣어 주세요. 링크는 10분간 유효합니다.")
    @PostMapping("/images/uploadUrls")
    public ApiResponse<List<ImageUploadUrlResponse>> issueImageUploadUrls(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestBody @Valid ImageUploadUrlRequest request) {

        return ApiResponse.ok("이미지 업로드 링크를 발급했습니다.",
                productImageService.issueUploadUrls(authMember.memberId(), request));
    }

    @Operation(summary = "내 판매 물품 상태별 개수", description = "내 판매 목록 탭에 표시할 상태별 개수를 조회합니다. 삭제된 상품은 제외합니다. "
            + "hidden 규칙은 내 판매 물품 조회와 같습니다. 오늘(한국 시간 자정부터) 등록 수 · 사진 검수 수와 하루 한도도 함께 줍니다 "
            + "(hidden과 상관없이 셈).")
    @GetMapping("/me/counts")
    public ApiResponse<MyProductCountResponse> getMyProductCounts(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(required = false) Boolean hidden) {

        return ApiResponse.ok("내 판매 상품 개수를 조회했습니다.",
                productService.getMyProductCounts(authMember.memberId(), hidden));
    }
}
