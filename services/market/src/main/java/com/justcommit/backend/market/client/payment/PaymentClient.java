package com.justcommit.backend.market.client.payment;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.client.exception.InternalApiResultUnknownException;
import com.justcommit.backend.common.client.exception.InternalApiUnavailableException;
import com.justcommit.backend.common.response.ApiResponse;
import com.justcommit.backend.market.client.payment.PaymentApi.PaymentRequest;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class PaymentClient implements PaymentUseCase {

    private static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    private static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";

    private final PaymentApi paymentApi;

    // 결제 결과를 분류만 한다. 예외를 밖으로 던지지 않는다 (ADR 0023)
    @Override
    public PaymentOutcome pay(Long orderId, Long buyerId, BigDecimal amount) {
        ApiResponse<PaymentResult> response;
        try {
            response = paymentApi.pay(orderId, new PaymentRequest(orderId, buyerId, amount));
        } catch (InternalApiErrorException e) {
            return classifyError(e);
        } catch (InternalApiUnavailableException e) {
            return PaymentOutcome.notDelivered();
        } catch (InternalApiResultUnknownException e) {
            // 응답 타임아웃 · 응답 중 끊김
            return PaymentOutcome.unknown(isTimeout(e) ? "TIMEOUT" : "RESPONSE_INTERRUPTED");
        } catch (RuntimeException e) {
            // 응답 본문을 읽지 못함 등. payment가 처리했는지 알 수 없다
            log.warn("결제 응답을 해석하지 못함, 결과 불명: orderId={}", orderId, e);
            return PaymentOutcome.unknown("RESPONSE_UNREADABLE");
        }
        return classifyBody(orderId, response);
    }

    // payment는 잔액 부족 · 지갑 없음 · 중복 결제를 200 응답의 상태 값으로 알린다
    private PaymentOutcome classifyBody(Long orderId, ApiResponse<PaymentResult> response) {
        PaymentStatus status = (response == null || response.data() == null) ? null : response.data().status();
        if (status == null) {
            log.warn("결제 응답에 상태 값이 없음, 결과 불명: orderId={}", orderId);
            return PaymentOutcome.unknown("RESPONSE_UNREADABLE");
        }
        return switch (status) {
            case PAID, ALREADY_PAID -> PaymentOutcome.paid();
            case INSUFFICIENT_BALANCE -> PaymentOutcome.insufficientBalance();
            case WALLET_NOT_FOUND -> PaymentOutcome.rejected(WALLET_NOT_FOUND);
            default -> {
                log.warn("결제 응답에 모르는 상태 값, 결과 불명: orderId={}, status={}", orderId, status);
                yield PaymentOutcome.unknown("UNEXPECTED_STATUS_" + status);
            }
        };
    }

    // 4xx는 payment가 거절한 것이 확실하다 (401 · 403 포함). 5xx는 처리 중 실패했을 수 있어 결과 불명
    private PaymentOutcome classifyError(InternalApiErrorException e) {
        if (INSUFFICIENT_BALANCE.equals(e.code())) {
            return PaymentOutcome.insufficientBalance();
        }
        String reason = "HTTP_" + e.status() + (e.code() == null ? "" : "_" + e.code());
        if (e.status() >= 400 && e.status() < 500) {
            return PaymentOutcome.rejected(WALLET_NOT_FOUND.equals(e.code()) ? WALLET_NOT_FOUND : reason);
        }
        return PaymentOutcome.unknown(reason);
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }

    // 환불 결과를 분류만 한다. 예외를 밖으로 던지지 않는다 (#282)
    @Override
    public RefundOutcome refundCancelledItem(Long orderItemId, Long orderId, Long buyerId, BigDecimal amount) {
        return refund("orderItemId=" + orderItemId, () ->
                paymentApi.refundOrderItem(orderItemId, orderItemId, new PaymentRequest(orderId, buyerId, amount)));
    }

    @Override
    public RefundOutcome refundShippingFee(Long sellerOrderId, Long orderId, Long buyerId, BigDecimal amount) {
        return refund("sellerOrderId=" + sellerOrderId, () ->
                paymentApi.refundShippingFee(sellerOrderId, sellerOrderId, new PaymentRequest(orderId, buyerId, amount)));
    }

    // 환불은 되돌리는 동작이라 연결 실패도 결과 불명으로 보고 같은 키로 다시 요청한다
    private RefundOutcome refund(String target, Supplier<ApiResponse<PaymentResult>> call) {
        ApiResponse<PaymentResult> response;
        try {
            response = call.get();
        } catch (InternalApiErrorException e) {
            String reason = "HTTP_" + e.status() + (e.code() == null ? "" : "_" + e.code());
            return (e.status() >= 400 && e.status() < 500) ? RefundOutcome.rejected(reason) : RefundOutcome.unknown(reason);
        } catch (InternalApiUnavailableException e) {
            return RefundOutcome.unknown("CONNECTION_FAILED");
        } catch (InternalApiResultUnknownException e) {
            return RefundOutcome.unknown(isTimeout(e) ? "TIMEOUT" : "RESPONSE_INTERRUPTED");
        } catch (RuntimeException e) {
            log.warn("환불 응답을 해석하지 못함, 결과 불명: {}", target, e);
            return RefundOutcome.unknown("RESPONSE_UNREADABLE");
        }
        PaymentStatus status = (response == null || response.data() == null) ? null : response.data().status();
        if (status == PaymentStatus.REFUNDED || status == PaymentStatus.ALREADY_REFUNDED) {
            return RefundOutcome.refunded();
        }
        log.warn("환불 응답에 모르는 상태 값, 결과 불명: {}, status={}", target, status);
        return RefundOutcome.unknown(status == null ? "RESPONSE_UNREADABLE" : "UNEXPECTED_STATUS_" + status);
    }
}
