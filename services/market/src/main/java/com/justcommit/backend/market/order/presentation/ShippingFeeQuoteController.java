package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.shipment.application.ShippingFeeTableService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 주문서 배송비 미리 계산 (#618): 배송지를 고르면 제주 · 도서산간 할증을 결제 전에 보여 준다.
// 주문 생성(OrderService.create)과 같은 ShippingFeeTableService 계산을 쓴다. 저장된 요금표만 읽고 택배사는 부르지 않는다
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class ShippingFeeQuoteController {

    private final ShippingFeeTableService shippingFeeTableService;

    @GetMapping("/shippingFees")
    public ResponseEntity<ShippingFeeQuoteResponse> quote(
            @AuthenticationPrincipal AuthMember member, @Valid @ModelAttribute ShippingFeeQuoteRequest request) {
        if (member == null) {
            throw new BusinessException(OrderErrorCode.AUTH_REQUIRED);
        }
        return ResponseEntity.ok(ShippingFeeQuoteResponse.of(
                shippingFeeTableService.quote(request.zipcode()), request.sellerIds()));
    }
}
