package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.order.application.OrderService;
import com.justcommit.backend.market.order.application.OrderReadService;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderReadService orderReadService;

    @GetMapping
    public ResponseEntity<PagingResponse<OrderResponse>> list(@AuthenticationPrincipal AuthMember member, @ModelAttribute OrderListRequest request) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return ResponseEntity.ok(orderReadService.buyerOrders(member.memberId(), request));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDetailResponse> detail(@AuthenticationPrincipal AuthMember member, @PathVariable Long orderId) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return ResponseEntity.ok(orderReadService.buyerOrder(member.memberId(), orderId));
    }

    @GetMapping("/seller")
    public ResponseEntity<PagingResponse<SellerOrderResponse>> sellerList(
            @AuthenticationPrincipal AuthMember member, @ModelAttribute OrderListRequest request) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return ResponseEntity.ok(orderReadService.sellerOrders(member.memberId(), request));
    }

    @GetMapping("/seller/{sellerOrderId}")
    public ResponseEntity<SellerOrderDetailResponse> sellerDetail(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long sellerOrderId) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return ResponseEntity.ok(orderReadService.sellerOrder(member.memberId(), sellerOrderId));
    }

    @PostMapping("/cart")
    public ResponseEntity<OrderCreateResponse> createFromCart(
            @AuthenticationPrincipal AuthMember member,
            @Valid @RequestBody CartOrderCreateRequest request) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return ResponseEntity.ok(orderService.create(member.memberId(), request));
    }

    @PostMapping("/{orderId}/pay")
    public ResponseEntity<OrderCreateResponse> payPendingOrder(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long orderId) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return ResponseEntity.ok(orderService.repayment(member.memberId(), orderId));
    }
}
