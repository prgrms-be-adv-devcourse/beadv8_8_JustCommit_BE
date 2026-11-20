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

    // 판매자 수락 (#284): 남은 수락 대기 주문상품을 모두 수락한다. 이미 처리됐으면 409
    @PostMapping("/seller/{sellerOrderId}/accept")
    public ResponseEntity<Void> acceptSellerOrder(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long sellerOrderId) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        orderService.acceptSellerOrder(member.memberId(), sellerOrderId);
        return ResponseEntity.ok().build();
    }

    // 판매자 거절 (#284): 고른 주문상품은 환불, 나머지는 수락한다. 이미 처리됐으면 409
    @PostMapping("/seller/{sellerOrderId}/reject")
    public ResponseEntity<Void> rejectSellerOrderItems(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long sellerOrderId,
            @Valid @RequestBody SellerOrderRejectRequest request) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        orderService.rejectSellerOrderItems(member.memberId(), sellerOrderId, request.orderItemIds());
        return ResponseEntity.ok().build();
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

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthMember member, @PathVariable Long orderId) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        orderService.cancel(member.memberId(), orderId);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/items/{orderItemId}/cancel")
    public ResponseEntity<Void> cancelItem(@AuthenticationPrincipal AuthMember member, @PathVariable Long orderItemId) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        orderService.itemCancel(member.memberId(), orderItemId);

        return ResponseEntity.ok().build();
    }

    // 구매자 반품 신청 (#382): 배송 완료 뒤 반품 기간 안에 주문상품 하나씩
    @PostMapping("/items/{orderItemId}/return")
    public ResponseEntity<Void> requestReturn(@AuthenticationPrincipal AuthMember member, @PathVariable Long orderItemId,
                                              @Valid @RequestBody ReturnRequestCreateRequest request) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        orderService.requestReturn(member.memberId(), orderItemId, request.reason(), request.reasonDetail());

        return ResponseEntity.ok().build();
    }

    @PostMapping("/items/{orderItemId}/confirm")
    public ResponseEntity<Void> confirm(@AuthenticationPrincipal AuthMember member, @PathVariable Long orderItemId) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        orderService.confirm(member.memberId(), orderItemId);

        return ResponseEntity.ok().build();
    }
}
