package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.pg.TossConfirmRejectedException;
import com.justcommit.backend.payment.infrastructure.pg.TossConfirmResponse;
import com.justcommit.backend.payment.infrastructure.pg.TossConfirmResultUnknownException;
import com.justcommit.backend.payment.infrastructure.pg.TossPaymentClient;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmRequest;
import com.justcommit.backend.payment.presentation.dto.ChargeConfirmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

// 충전 승인 오케스트레이터: TX1 → 토스 승인 호출(트랜잭션 밖) → TX2
// 토스 호출 동안 DB 트랜잭션과 지갑 잠금을 잡지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
public class ChargeConfirmFacade {

    private static final String TOSS_DONE = "DONE";

    private final ChargeService chargeService;
    private final TossPaymentClient tossPaymentClient;

    public ChargeConfirmResponse confirmCharge(Long memberId, Long chargeId, ChargeConfirmRequest request) {
        // TX1: READY → PENDING 커밋
        ChargeConfirmResponse pending = chargeService.startConfirm(memberId, chargeId, request);

        TossConfirmResponse tossResponse;
        try {
            tossResponse = tossPaymentClient.confirm(
                    request.getPaymentKey(), request.getOrderId(), request.getAmount());
        } catch (TossConfirmRejectedException e) {
            try {
                chargeService.fail(chargeId);
            } catch (RuntimeException failError) {
                // FAILED 반영 실패 → PENDING으로 남고 #363이 정리. 사용자에게는 거절 사유를 그대로 돌려준다
                log.error("토스 거절 후 FAILED 반영 실패: chargeId={}, code={}", chargeId, e.getCode(), failError);
            }
            throw new BusinessException(PaymentErrorCode.PG_CONFIRM_FAILED, e.getMessage());
        } catch (TossConfirmResultUnknownException e) {
            // 결과 불명 → PENDING 유지, 복구 스케줄러(#363)가 확정
            return pending;
        }

        if (!matches(tossResponse, pending, request.getPaymentKey())) {
            // 토스에서는 승인됐을 수 있지만 우리 기록과 달라 반영하지 않는다 (보상 취소는 #402)
            log.error("토스 승인 응답이 충전 기록과 다름: chargeId={}, orderId={}, 충전 금액={}, 토스 상태={}, 토스 금액={}",
                    chargeId, pending.getPgOrderNo(), pending.getAmount(),
                    tossResponse == null ? null : tossResponse.status(),
                    tossResponse == null ? null : tossResponse.totalAmount());
            return pending;
        }

        try {
            // TX2: APPROVED + 지갑 적립 + 거래 내역
            return chargeService.completeConfirm(chargeId, tossResponse.approvedAt().toLocalDateTime());
        } catch (RuntimeException e) {
            // 토스는 DONE인데 DB 반영 실패 → PENDING 유지, 복구 스케줄러(#363)가 다시 반영
            log.error("토스 승인 후 충전 반영 실패: chargeId={}, orderId={}", chargeId, pending.getPgOrderNo(), e);
            return pending;
        }
    }

    private boolean matches(TossConfirmResponse toss, ChargeConfirmResponse charge, String paymentKey) {
        return toss != null
                && TOSS_DONE.equals(toss.status())
                && paymentKey.equals(toss.paymentKey())
                && charge.getPgOrderNo().equals(toss.orderId())
                && toss.totalAmount() != null
                && toss.approvedAt() != null
                && charge.getAmount().compareTo(BigDecimal.valueOf(toss.totalAmount())) == 0;
    }
}
