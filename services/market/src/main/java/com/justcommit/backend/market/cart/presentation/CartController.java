package com.justcommit.backend.market.cart.presentation;

import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.cart.application.CartRecommendationService;
import com.justcommit.backend.market.cart.application.CartService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final CartRecommendationService cartRecommendationService;

    @GetMapping
    public ResponseEntity<CartResponse> find(@AuthenticationPrincipal AuthMember member) {
        return ResponseEntity.ok(cartService.find(member.memberId()));
    }

    // 장바구니 · 최근 구매 확정 상품과 함께 키우기 좋은 판매 중 상품. ai가 느리거나 죽으면 빈 목록 (장바구니 조회는 별개)
    @GetMapping("/recommendations")
    public ApiResponse<List<CartRecommendationResponse>> recommendations(
            @AuthenticationPrincipal AuthMember member,
            @RequestParam(defaultValue = "" + CartRecommendationService.DEFAULT_SIZE) int size) {
        return ApiResponse.ok("장바구니 추천 상품을 조회했습니다.",
                cartRecommendationService.recommend(member.memberId(), size));
    }

    @PostMapping("/items")
    public ResponseEntity<Long> addItem(@AuthenticationPrincipal AuthMember member,
                                        @Valid @RequestBody CartItemAddRequest cartItemAddRequest) {
        return ResponseEntity.ok(cartService.addItemToCart(member.memberId(), cartItemAddRequest.productId()));
    }

    @PatchMapping("/items/prices")
    public ApiResponse<CartResponse> updatePrices(@AuthenticationPrincipal AuthMember member,
                                                  @Valid @RequestBody CartItemPriceUpdateRequest request) {
        return ApiResponse.ok("장바구니 가격을 갱신했습니다.", cartService.updatePrices(member.memberId(), request.items()));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Void> deleteItem(@AuthenticationPrincipal AuthMember member, @PathVariable Long productId) {
        cartService.removeItemFromCart(member.memberId(), productId);
        return ResponseEntity.ok().build();
    }

}
