package com.justcommit.backend.payment.presentation.internal;

import static com.justcommit.backend.common.client.InternalApiHeaders.IDEMPOTENCY_KEY;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.payment.application.PaymentResult;
import com.justcommit.backend.payment.application.PaymentService;
import com.justcommit.backend.payment.presentation.internal.dto.PaymentRequest;
import com.justcommit.backend.payment.presentation.internal.dto.SettlementPayoutRequest;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 서비스 간 내부 API (Gateway가 라우팅하지 않음). 규칙 → docs/reference/internal-apis.md
// Idempotency-Key는 업무 ID와 같아야 한다. 없으면 공통 처리기가 400 MISSING_HEADER
@RestController
@RequestMapping("/internal/v1")
@RequiredArgsConstructor
public class PaymentInternalController {

    private final PaymentService paymentService;

    // market 결제. 잔액 부족 · 지갑 없음 · 중복 결제는 200 응답의 결과 상태로 알린다
    @PostMapping("/payments")
    public ApiResponse<PaymentResult> pay(@RequestHeader(IDEMPOTENCY_KEY) Long idempotencyKey,
                                          @RequestBody PaymentRequest request) {
        requireSameKey(idempotencyKey, request.orderId(), "orderId");
        return ApiResponse.ok("결제를 처리했습니다.",
                paymentService.pay(request.orderId(), request.buyerId(), request.amount()));
    }

    // market 상품 금액 환불 (취소 · 거절 · 반품). 확정 실패는 BusinessException → 4xx
    @PostMapping("/refunds/orderItems/{orderItemId}")
    public ApiResponse<PaymentResult> refundOrderItem(@RequestHeader(IDEMPOTENCY_KEY) Long idempotencyKey,
                                                      @PathVariable Long orderItemId,
                                                      @RequestBody PaymentRequest request) {
        requireSameKey(idempotencyKey, orderItemId, "orderItemId");
        return ApiResponse.ok("환불했습니다.",
                paymentService.refundCancelledItem(orderItemId, request.buyerId(), request.amount()));
    }

    // market 배송비 환불. 확정 실패는 BusinessException → 4xx
    @PostMapping("/refunds/shippingFees/{sellerOrderId}")
    public ApiResponse<PaymentResult> refundShippingFee(@RequestHeader(IDEMPOTENCY_KEY) Long idempotencyKey,
                                                        @PathVariable Long sellerOrderId,
                                                        @RequestBody PaymentRequest request) {
        requireSameKey(idempotencyKey, sellerOrderId, "sellerOrderId");
        return ApiResponse.ok("배송비를 환불했습니다.",
                paymentService.refundShippingFee(sellerOrderId, request.buyerId(), request.amount()));
    }

    // settlement 정산금 지급. 같은 settlementId로 다시 오면 지급하지 않고 성공
    @PostMapping("/settlementPayouts")
    public ApiResponse<Void> payoutSettlement(@RequestHeader(IDEMPOTENCY_KEY) Long idempotencyKey,
                                              @RequestBody SettlementPayoutRequest request) {
        requireSameKey(idempotencyKey, request.settlementId(), "settlementId");
        paymentService.distributeSettlement(
                request.settlementId(), request.sellerId(), request.payoutAmount(), request.fee());
        return ApiResponse.ok("정산금을 지급했습니다.");
    }

    private static void requireSameKey(Long idempotencyKey, Long businessId, String name) {
        if (!Objects.equals(idempotencyKey, businessId)) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA, "Idempotency-Key는 " + name + "와 같아야 합니다.");
        }
    }
}
