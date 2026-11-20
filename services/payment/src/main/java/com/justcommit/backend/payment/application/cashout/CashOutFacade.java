package com.justcommit.backend.payment.application.cashout;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.client.member.MemberAccount;
import com.justcommit.backend.payment.client.member.MemberAccountClient;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.bank.BankTransferClient;
import com.justcommit.backend.payment.infrastructure.bank.BankTransferRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

// 인출 신청 흐름. 트랜잭션을 걸지 않는다: member 계좌 조회와 은행 지급은 DB 트랜잭션, 지갑 잠금 밖에서 한다
// 1. 같은 키 인출 건 확인 (잠금 없음) → 있으면 처음 결과, 금액이 다르면 409
// 2. member 계좌 조회 → 실패하면 지갑을 건드리지 않고 거절
// 3. Tx1: 지갑 잠금, 검증, 차감, 인출 건 PENDING, CASH_OUT 원장
// 4. 은행 지급 한 번 (멱등 키 = 인출 건 ID) → 성공이면 COMPLETED, 예외면 PENDING 유지
@Slf4j
@Component
@RequiredArgsConstructor
public class CashOutFacade {

    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final CashOutService cashOutService;
    private final MemberAccountClient memberAccountClient;
    private final BankTransferClient bankTransferClient;

    public CashOutResult requestCashOut(Long memberId, String idempotencyKey, BigDecimal amount) {
        validateIdempotencyKey(idempotencyKey);
        CashOutService.requireValidAmount(amount);

        Optional<CashOutResult> existing = cashOutService.findExisting(memberId, idempotencyKey, amount);
        if (existing.isPresent()) {
            return existing.get();
        }

        MemberAccount account = memberAccountClient.getAccount(memberId);

        CashOutService.RequestOutcome outcome = cashOutService.request(memberId, idempotencyKey, amount, account);
        if (!outcome.created()) {
            return outcome.result();
        }

        Long cashOutId = outcome.result().cashOutId();
        transfer(cashOutId, account, amount);
        return cashOutService.getResult(cashOutId);
    }

    private void transfer(Long cashOutId, MemberAccount account, BigDecimal amount) {
        try {
            bankTransferClient.transfer(new BankTransferRequest(
                    String.valueOf(cashOutId), account.bankCode(), account.accountNo(), account.accountHolder(), amount));
        } catch (RuntimeException e) {
            // 예외 메시지에 계좌가 섞일 수 있어 종류만 남긴다
            log.error("인출 지급 실패, PENDING 유지: cashOutId={}, cause={}", cashOutId, e.getClass().getSimpleName());
            return;
        }

        if (!cashOutService.complete(cashOutId)) {
            log.warn("인출 건이 PENDING이 아니어서 완료로 바꾸지 않음: cashOutId={}", cashOutId);
        }
    }

    private static void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(PaymentErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }
}
