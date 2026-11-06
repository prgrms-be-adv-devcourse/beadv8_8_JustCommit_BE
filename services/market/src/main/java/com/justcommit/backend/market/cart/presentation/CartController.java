package com.justcommit.backend.market.cart.presentation;

import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.cart.application.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<CartResponse> find(@AuthenticationPrincipal AuthMember member) {
        return ResponseEntity.ok(cartService.find(member.memberId()));
    }

    @PostMapping("/items")
    public ResponseEntity<Long> addItem(@AuthenticationPrincipal AuthMember member,
                                        @Valid @RequestBody CartItemAddRequest cartItemAddRequest) {
        return ResponseEntity.ok(cartService.addItemToCart(member.memberId(), cartItemAddRequest.productId()));
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Void> deleteItem(@AuthenticationPrincipal AuthMember member, @PathVariable Long productId) {
        cartService.removeItemFromCart(member.memberId(), productId);
        return ResponseEntity.ok().build();
    }

}
