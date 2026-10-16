package com.justcommit.backend.product.product.presentation;


import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.product.image.application.ProductImageService;
import com.justcommit.backend.product.image.presentation.dto.ImageUploadUrlRequest;
import com.justcommit.backend.product.image.presentation.dto.ImageUploadUrlResponse;
import com.justcommit.backend.product.product.application.ProductService;
import com.justcommit.backend.product.product.presentation.dto.*;
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

    @Operation(summary = "상품 등록", description = "로그인한 회원을 판매자로 상품을 등록합니다. 금지 품종은 등록할 수 없습니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductDetailResponse> createProduct(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestBody @Valid  ProductRequest request) {

        return ApiResponse.created("상품이 성공적으로 등록되었습니다.",
                productService.createProduct(authMember.memberId(), request));
    }

    @Operation(summary = "상품 목록 조회", description = "키워드(제목·품종명), 품종, 카테고리, 광량·물주기·난이도(품종 기준), 가격 범위로 검색합니다. "
            + "onSale=true면 판매 중인 상품만 조회합니다. 삭제·숨김 상품은 항상 제외됩니다.")
    @GetMapping
    public ApiResponse<PagingResponse<ProductSearchResponse>> searchProducts (
            @ModelAttribute ProductSearchRequest request) {

        return ApiResponse.ok("상품 목록을 조회했습니다.",
                PagingResponse.from(productService.searchProducts(request)));
    }

    @Operation(summary = "상품 상세 조회", description = "품종 정보와 사진 목록을 포함합니다. 숨긴 상품은 판매자 본인만 조회할 수 있습니다.")
    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> getProductDetail(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId) {

        Long memberId = authMember == null ? null : authMember.memberId();
        return ApiResponse.ok("상품 상세를 조회했습니다.",
                productService.getProductDetail(productId, memberId));
    }

    @Operation(summary="상품 수정", description  ="판매 중인 본인 상품만 수정할 수 있습니다.")
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
    @PostMapping("/images/upload-urls")
    public ApiResponse<List<ImageUploadUrlResponse>> issueImageUploadUrls(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestBody @Valid ImageUploadUrlRequest request) {

        return ApiResponse.ok("이미지 업로드 링크를 발급했습니다.",
                productImageService.issueUploadUrls(authMember.memberId(), request));
    }

    @Operation(summary = "내 판매 물품 상태별 개수", description = "내 판매 목록 탭에 표시할 상태별 개수를 조회합니다. 삭제된 상품은 제외합니다. "
            + "hidden 규칙은 내 판매 물품 조회와 같습니다.")
    @GetMapping("/me/counts")
    public ApiResponse<MyProductCountResponse> getMyProductCounts(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(required = false) Boolean hidden) {

        return ApiResponse.ok("내 판매 상품 개수를 조회했습니다.",
                productService.getMyProductCounts(authMember.memberId(), hidden));
    }
}
