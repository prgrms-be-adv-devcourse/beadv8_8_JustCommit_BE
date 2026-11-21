package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.order.application.OrderShipmentTrackingService;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 배송 조회 (#588) · 반품 택배 조회 (#617): 배송 단계 · 시각 + 택배사의 남은 거리 · 지점. 본인 주문만, 남의 것은 404
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class ShipmentTrackingController {

    private final OrderShipmentTrackingService trackingService;

    @GetMapping("/seller/{sellerOrderId}/shipment")
    public ResponseEntity<ShipmentTrackingResponse> sellerShipment(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long sellerOrderId) {
        Long memberId = requireMember(member);
        return ResponseEntity.ok(ShipmentTrackingResponse.of(sellerOrderId,
                trackingService.sellerShipment(memberId, sellerOrderId)));
    }

    @GetMapping("/{orderId}/sellerOrders/{sellerOrderId}/shipment")
    public ResponseEntity<ShipmentTrackingResponse> buyerShipment(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long orderId, @PathVariable Long sellerOrderId) {
        Long memberId = requireMember(member);
        return ResponseEntity.ok(ShipmentTrackingResponse.of(sellerOrderId,
                trackingService.buyerShipment(memberId, orderId, sellerOrderId)));
    }

    // 반품 택배 (#617): 주문상품 단위
    @GetMapping("/seller/{sellerOrderId}/items/{orderItemId}/returnShipment")
    public ResponseEntity<ReturnShipmentTrackingResponse> sellerReturnShipment(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long sellerOrderId, @PathVariable Long orderItemId) {
        Long memberId = requireMember(member);
        return ResponseEntity.ok(ReturnShipmentTrackingResponse.of(orderItemId,
                trackingService.sellerReturnShipment(memberId, sellerOrderId, orderItemId)));
    }

    @GetMapping("/{orderId}/items/{orderItemId}/returnShipment")
    public ResponseEntity<ReturnShipmentTrackingResponse> buyerReturnShipment(
            @AuthenticationPrincipal AuthMember member, @PathVariable Long orderId, @PathVariable Long orderItemId) {
        Long memberId = requireMember(member);
        return ResponseEntity.ok(ReturnShipmentTrackingResponse.of(orderItemId,
                trackingService.buyerReturnShipment(memberId, orderId, orderItemId)));
    }

    private Long requireMember(AuthMember member) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return member.memberId();
    }
}
