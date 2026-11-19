package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.WalletType;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 회원 지갑 입금. 입금한 뒤 미수금이 있으면 입금액 안에서 먼저 빼 REVENUE로 옮긴다 (미수금 차감).
 *
 * <p>충전 승인, 양수 정산금 지급, HOLDING 환불이 모두 이 클래스로 입금한다.
 * 호출자의 트랜잭션 안에서만 동작하며, 회원 지갑은 호출자가 이미 잠가서 넘긴다.</p>
 *
 * <p>기록 순서: 입금 credit → 입금 행 → 회원 debit, 미수금 감소 → 회원 {@code RECEIVABLE_COLLECTION} OUT 행
 * → REVENUE credit → REVENUE {@code RECEIVABLE_COLLECTION} IN 행. 원장을 위에서부터 따라가면 잔액이 이어진다.
 * 차감 행 두 개의 ref_id는 입금 행 ID라서 입금 1건당 차감은 한 번만 기록된다 (UK wallet_id, ref_type, ref_id).</p>
 *
 * <p>미수금이 0이면 REVENUE를 조회하지도 잠그지도 않는다. REVENUE는 하나뿐인 행이라 매번 잠그면
 * 서로 다른 회원의 충전, 환불이 이 행에서 직렬화된다. 판정은 잠긴 회원 지갑의 미수금으로 하므로,
 * 미수금을 늘리는 경로는 반드시 회원 지갑을 먼저 잠가야 한다 ({@link Wallet#increaseReceivable}).</p>
 */
@Component
@RequiredArgsConstructor
public class MemberWalletDepositor {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    /**
     * REVENUE를 아직 잠그지 않은 호출자용 (충전, 환불). 미수금이 있을 때만 REVENUE를 잠근다.
     * 잠금 순서는 회원 지갑 → (호출자가 잡은 충전 행 또는 HOLDING) → REVENUE.
     *
     * @param lockedMemberWallet 호출자가 잠근 회원 지갑
     * @param amount             입금액 (0보다 커야 함)
     * @param depositRow         입금 직후 지갑으로 입금 행을 만드는 팩토리. 행 금액은 amount와 같아야 한다
     * @return 미수금 차감액 (없으면 0)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal deposit(
            Wallet lockedMemberWallet,
            BigDecimal amount,
            Function<Wallet, WalletTransaction> depositRow
    ) {
        return deposit(lockedMemberWallet, amount, depositRow, this::lockRevenueWallet);
    }

    /**
     * REVENUE를 이미 잠근 호출자용 (정산 지급). 넘겨받은 REVENUE 지갑을 다시 잠그지 않고 쓴다.
     *
     * @param lockedRevenueWallet 호출자가 잠근 REVENUE 지갑
     * @see #deposit(Wallet, BigDecimal, Function)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal deposit(
            Wallet lockedMemberWallet,
            BigDecimal amount,
            Function<Wallet, WalletTransaction> depositRow,
            Wallet lockedRevenueWallet
    ) {
        if (lockedRevenueWallet == null || lockedRevenueWallet.getWalletType() != WalletType.REVENUE) {
            throw new IllegalArgumentException("잠긴 REVENUE 지갑이 필요합니다.");
        }
        return deposit(lockedMemberWallet, amount, depositRow, () -> lockedRevenueWallet);
    }

    private BigDecimal deposit(
            Wallet memberWallet,
            BigDecimal amount,
            Function<Wallet, WalletTransaction> depositRow,
            Supplier<Wallet> revenueWallet
    ) {
        if (memberWallet == null || memberWallet.getWalletType() != WalletType.MEMBER) {
            throw new IllegalArgumentException("회원 지갑에만 입금할 수 있습니다.");
        }

        // 입금과 입금 행 (balance_after, receivable_after = 입금 직후 값)
        memberWallet.credit(amount);
        WalletTransaction deposit = depositRow.apply(memberWallet);
        if (deposit.getAmount().compareTo(amount) != 0) {
            throw new IllegalStateException("입금 행 금액이 입금액과 다릅니다: " + deposit.getAmount() + " != " + amount);
        }
        walletTransactionRepository.save(deposit);

        // 미수금이 없으면 REVENUE를 건드리지 않는다
        BigDecimal collected = memberWallet.getReceivable().min(amount);
        if (collected.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        // IDENTITY라 persist 시점에 같은 인스턴스에 ID가 채워진다
        Long depositId = deposit.getId();
        if (depositId == null) {
            throw new IllegalStateException("입금 행 ID가 없어 미수금 차감을 기록할 수 없습니다.");
        }

        // 회원 지갑에서 차감
        memberWallet.debit(collected);
        memberWallet.decreaseReceivable(collected);
        walletTransactionRepository.save(WalletTransaction.receivableCollectionOut(
                memberWallet, depositId, collected, deposit.getRefOccurredTime()));

        // REVENUE 입금
        Wallet revenue = revenueWallet.get();
        revenue.credit(collected);
        walletTransactionRepository.save(WalletTransaction.receivableCollectionIn(
                revenue, depositId, collected, deposit.getRefOccurredTime()));

        return collected;
    }

    private Wallet lockRevenueWallet() {
        return walletRepository.findByWalletTypeForUpdate(WalletType.REVENUE)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));
    }
}
