package com.justcommit.backend.payment.application.cashout;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.client.member.MemberAccount;
import com.justcommit.backend.payment.domain.CashOut;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.crypto.AccountNoCipher;
import com.justcommit.backend.payment.infrastructure.repository.CashOutRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import com.justcommit.backend.payment.presentation.dto.CashOutHistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

// 인출의 DB 작업. 트랜잭션은 메서드 하나씩이고, member, 은행 호출은 CashOutFacade가 트랜잭션 밖에서 한다
@Service
@RequiredArgsConstructor
public class CashOutService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final CashOutRepository cashOutRepository;
    private final AccountNoCipher accountNoCipher;
    private final Clock clock;

    // 잠금 없이 같은 키 인출 건을 찾는다. 금액이 다르면 409
    @Transactional(readOnly = true)
    public Optional<CashOutResult> findExisting(Long memberId, String idempotencyKey, BigDecimal amount) {
        return walletRepository.findByMemberId(memberId)
                .flatMap(wallet -> cashOutRepository.findByWalletIdAndIdempotencyKey(wallet.getId(), idempotencyKey))
                .map(cashOut -> toExistingResult(cashOut, amount));
    }

    // Tx1: 회원 지갑을 잠그고 검증 → 차감 → 인출 건 PENDING → CASH_OUT 원장. 회원 지갑 하나만 잠근다
    // 같은 키 인출 건이 이미 있으면(동시 요청) 아무것도 바꾸지 않고 처음 결과를 돌려준다 (created = false)
    @Transactional
    public RequestOutcome request(Long memberId, String idempotencyKey, BigDecimal amount, MemberAccount account) {
        requireValidAmount(amount);

        // 지갑이 없으면 잔액 0으로 보고 한도 초과 (지갑을 만들지 않음)
        Wallet wallet = walletRepository.findByMemberIdForUpdate(memberId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.CASH_OUT_LIMIT_EXCEEDED));

        Optional<CashOut> existing = cashOutRepository.findByWalletIdAndIdempotencyKey(wallet.getId(), idempotencyKey);
        if (existing.isPresent()) {
            return new RequestOutcome(toExistingResult(existing.get(), amount), false);
        }

        if (wallet.getReceivable().signum() > 0) {
            throw new BusinessException(PaymentErrorCode.RECEIVABLE_REMAINING);
        }
        if (!wallet.hasEnoughBalance(amount)) {
            throw new BusinessException(PaymentErrorCode.CASH_OUT_LIMIT_EXCEEDED);
        }

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        wallet.debit(amount);
        CashOut cashOut = cashOutRepository.saveAndFlush(CashOut.pending(
                wallet.getId(), amount, idempotencyKey, account.bankCode(),
                accountNoCipher.encrypt(account.accountNo()), account.accountLast4(), account.accountHolder(), now));
        walletTransactionRepository.save(WalletTransaction.cashOut(wallet, cashOut.getId(), amount, now));

        return new RequestOutcome(new CashOutResult(cashOut.getId(), amount, cashOut.getStatus(),
                cashOut.getAccountLast4(), wallet.getBalance()), true);
    }

    // 지급 성공 반영: PENDING일 때만 COMPLETED. 지갑은 잠그지 않는다
    @Transactional
    public boolean complete(Long cashOutId) {
        return cashOutRepository.markCompleted(cashOutId, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)) == 1;
    }

    @Transactional(readOnly = true)
    public CashOutResult getResult(Long cashOutId) {
        CashOut cashOut = cashOutRepository.findById(cashOutId)
                .orElseThrow(() -> new IllegalStateException("인출 건이 없습니다: " + cashOutId));
        return toResult(cashOut);
    }

    // 인출 내역: 지갑이 없으면 빈 목록 (지갑을 만들지 않음)
    @Transactional(readOnly = true)
    public Page<CashOutHistoryResponse> getCashOuts(Long memberId, Pageable pageable) {
        return walletRepository.findByMemberId(memberId)
                .map(wallet -> cashOutRepository.findByWalletId(wallet.getId(), pageable)
                        .map(CashOutHistoryResponse::from))
                .orElseGet(() -> Page.empty(pageable));
    }

    private CashOutResult toExistingResult(CashOut cashOut, BigDecimal requestedAmount) {
        if (!cashOut.isSameRequest(requestedAmount)) {
            throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return toResult(cashOut);
    }

    private CashOutResult toResult(CashOut cashOut) {
        BigDecimal balanceAfter = walletTransactionRepository
                .findByWallet_IdAndRefTypeAndRefId(cashOut.getWalletId(), RefType.CASH_OUT, cashOut.getId())
                .map(WalletTransaction::getBalanceAfter)
                .orElseThrow(() -> new IllegalStateException("인출 원장 행이 없습니다: " + cashOut.getId()));
        return new CashOutResult(cashOut.getId(), cashOut.getAmount(), cashOut.getStatus(),
                cashOut.getAccountLast4(), balanceAfter);
    }

    // 0보다 큰 원 단위 금액만 (소수 금액은 numeric(19, 0) 칸에서 반올림되므로 거절)
    static void requireValidAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }
    }

    // created: 이번 요청이 차감하고 인출 건을 만들었는지 (false면 같은 키의 기존 결과)
    public record RequestOutcome(CashOutResult result, boolean created) {
    }
}
