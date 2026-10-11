package com.justcommit.backend.product.presentation.controller;


import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.product.application.ProductService;
import com.justcommit.backend.product.presentation.dto.ProductCreateRequest;
import com.justcommit.backend.product.presentation.dto.ProductDetailResponse;
import com.justcommit.backend.product.presentation.dto.ProductSearchRequest;
import com.justcommit.backend.product.presentation.dto.ProductSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "상품", description = "판매글 등록 및 조회 API")
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "상품 등록", description = "로그인한 회원을 판매자로 상품을 등록합니다. 금지 품종은 등록할 수 없습니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductDetailResponse> createProduct(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestBody @Valid  ProductCreateRequest request) {

        return ApiResponse.created("상품이 성공적으로 등록되었습니다.",
                productService.createProduct(authMember.memberId(), request));
    }

    @Operation(summary = "상품 목록 조회", description = "키워드(제목·품종명), 품종, 카테고리, 광량, 물주기, 난이도, 가격 범위로 검색합니다. 삭제·숨김 상품은 제외됩니다.")
    @GetMapping
    public ApiResponse<PagingResponse<ProductSearchResponse>> searchProducts (
            @ModelAttribute ProductSearchRequest request) {

        return ApiResponse.ok("상품 목록을 조회했습니다.",
                PagingResponse.from(productService.searchProducts(request)));
    }

    @Operation(summary = "상품 상세 조회", description = "품종 정보와 사진 목록을 포함합니다.")
    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> getProductDetail(@PathVariable Long productId){
        return ApiResponse.ok("상품 상세를 조회했습니다",
                productService.getProductDetail(productId));
    }

}
