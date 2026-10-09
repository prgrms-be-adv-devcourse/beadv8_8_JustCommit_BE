package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.order.application.OrderService;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping("/cart")
    public ResponseEntity<OrderCreateResponse> createFromCart(
            @AuthenticationPrincipal AuthMember member,
            @Valid @RequestBody CartOrderCreateRequest request) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return ResponseEntity.ok(orderService.create(member.memberId(), request));
    }
}
