package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentUseCase;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 사용자->시스템 순서로 지갑 잠금
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService implements PaymentUseCase {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final MemberQuery memberQuery; // 시스템 HOLDING

    @Override
    @Transactional
    public PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount) {
        validateRequest(orderId, buyerId, amount);

        Optional<Wallet> found = walletRepository.findByMemberIdForUpdate(buyerId);
        if (found.isEmpty()) {
            return PaymentResult.walletNotFound();
        }
        Wallet buyerWallet = found.get();

        if (walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(buyerWallet.getId(), RefType.PAYMENT, orderId)) {
            return PaymentResult.alreadyPaid(buyerWallet.getBalance());
        }

        if (!buyerWallet.hasEnoughBalance(amount)) {
            return PaymentResult.insufficientBalance(buyerWallet.getBalance());
        }

        buyerWallet.debit(amount);
        LocalDateTime now = LocalDateTime.now(); // 구매자 출금·HOLDING 입금을 같은 시각으로 기록

        walletTransactionRepository.save(
                WalletTransaction.paymentOut(buyerWallet, orderId, amount, now));


        Long holdingMemberId = memberQuery.getHoldingMemberId();
        Wallet holdingWallet = walletRepository
                .findByMemberIdForUpdate(holdingMemberId)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.WALLET_NOT_FOUND
                ));

        holdingWallet.credit(amount);
        walletTransactionRepository.save(
                WalletTransaction.holdingIn(holdingWallet, orderId, amount, now));


        return PaymentResult.paid(buyerWallet.getBalance());
    }

    private void validateRequest(Long orderId, Long buyerId, BigDecimal amount) {
        if (orderId == null || buyerId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT, "주문 결제 요청 값이 올바르지 않습니다.");
        }
    }

    @Override
    @Transactional
    public void distributeSettlement(
            Long settlementId,
            Long sellerId,
            BigDecimal payoutAmount,    // 판매자 정산금
            BigDecimal fee              // 수수료
    ) {
        // 요청값 검증
        if (settlementId == null || sellerId == null
                || payoutAmount == null || fee == null
                || payoutAmount.compareTo(BigDecimal.ZERO) <= 0
                || fee.compareTo(BigDecimal.ZERO) < 0
        ) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
        }

        Long holdingId = memberQuery.getHoldingMemberId();
        Long revenueId = memberQuery.getRevenueMemberId();

        // 판매자 지갑 잠금
        Wallet sellerWallet = walletRepository
                .findByMemberIdForUpdate(sellerId)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.WALLET_NOT_FOUND
                ));

        // 중복 정산 방지
        if (walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(
                sellerWallet.getId(), RefType.SETTLEMENT, settlementId)) {
            return;
        }

        // HOLDING 지갑 잠금
        Wallet holdingWallet = walletRepository
                .findByMemberIdForUpdate(holdingId)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.WALLET_NOT_FOUND
                ));

        // REVENUE 지갑 잠금
        Wallet revenueWallet = walletRepository
                .findByMemberIdForUpdate(revenueId)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.WALLET_NOT_FOUND
                ));

        BigDecimal totalAmount = payoutAmount.add(fee);
        LocalDateTime now = LocalDateTime.now();

        // HOLDING 출금
        holdingWallet.debit(totalAmount);
        walletTransactionRepository.save(
                WalletTransaction.settlementOut(
                        holdingWallet, settlementId, totalAmount, now
                )
        );

        // 판매자 지갑 적립
        sellerWallet.credit(payoutAmount);
        walletTransactionRepository.save(
                WalletTransaction.settlementIn(
                        sellerWallet, settlementId, payoutAmount, now
                )
        );

        // REVENUE 수수료 적립
        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            revenueWallet.credit(fee);
            walletTransactionRepository.save(
                    WalletTransaction.settlementFeeIn(
                            revenueWallet, settlementId, fee, now
                    )
            );
        }
    }

    /**
     * 택배가 수거된 판매자 주문의 배송비를 HOLDING에서 REVENUE로 옮긴다.
     * 시스템 지갑만 잠그며, 순서는 distributeSettlement와 같은 HOLDING -> REVENUE.
     */
    @Override
    @Transactional
    public void collectShippingFee(Long sellerOrderId, BigDecimal shippingFee) {
        // 요청값 검증
        if (sellerOrderId == null || shippingFee == null || shippingFee.signum() <= 0) {
            throw new BusinessException(
                    PaymentErrorCode.INVALID_AMOUNT,
                    "배송비 적립 요청 값이 올바르지 않습니다. sellerOrderId="
                            + sellerOrderId + ", shippingFee=" + shippingFee
            );
        }

        // HOLDING 지갑 잠금
        Wallet holdingWallet = walletRepository
                .findByMemberIdForUpdate(memberQuery.getHoldingMemberId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));

        // 중복 수신 방지
        if (walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(
                holdingWallet.getId(), RefType.SHIPPING_FEE, sellerOrderId)) {
            return;
        }

        // 수거 전 취소로 이미 구매자에게 환불된 배송비면 HOLDING에 남은 몫이 없다
        if (walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(
                holdingWallet.getId(), RefType.SHIPPING_FEE_REFUND, sellerOrderId)) {
            log.warn("이미 환불된 배송비라 수납하지 않음: sellerOrderId={}", sellerOrderId);
            return;
        }

        // REVENUE 지갑 잠금
        Wallet revenueWallet = walletRepository
                .findByMemberIdForUpdate(memberQuery.getRevenueMemberId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(); // HOLDING 출금·REVENUE 입금을 같은 시각으로 기록

        // HOLDING 출금 (잔액 부족 시 INSUFFICIENT_BALANCE 예외)
        holdingWallet.debit(shippingFee);
        walletTransactionRepository.save(
                WalletTransaction.shippingFeeOut(holdingWallet, sellerOrderId, shippingFee, now));

        // REVENUE 배송비 적립
        revenueWallet.credit(shippingFee);
        walletTransactionRepository.save(
                WalletTransaction.shippingFeeIn(revenueWallet, sellerOrderId, shippingFee, now));
    }

    /**
     * 취소된 주문상품 금액을 HOLDING에서 구매자 지갑으로 환불한다.
     * 실패는 BusinessException으로 던지며, 호출 측 트랜잭션(주문 취소)과 함께 롤백된다.
     */
    @Override
    @Transactional
    public PaymentResult refundCancelledItem(Long orderItemId, Long buyerId, BigDecimal amount) {
        return refundFromHolding(RefType.ORDER_ITEM_CANCEL_REFUND, orderItemId, buyerId, amount);
    }

    /**
     * 판매자 주문의 상품이 모두 취소됐을 때 배송비를 HOLDING에서 구매자 지갑으로 환불한다.
     * 실패는 BusinessException으로 던지며, 호출 측 트랜잭션(주문 취소)과 함께 롤백된다.
     */
    @Override
    @Transactional
    public PaymentResult refundShippingFee(Long sellerOrderId, Long buyerId, BigDecimal amount) {
        return refundFromHolding(RefType.SHIPPING_FEE_REFUND, sellerOrderId, buyerId, amount);
    }

    /** HOLDING 지갑에 보관 중인 돈을 구매자 지갑으로 환불
     * */
    private PaymentResult refundFromHolding(RefType refType, Long refId, Long buyerId, BigDecimal amount) {
        // 요청값 검증
        if (refId == null || buyerId == null || amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT, "환불 요청 값이 올바르지 않습니다.");
        }

        // 구매자 지갑 잠금
        Wallet buyerWallet = walletRepository
                .findByMemberIdForUpdate(buyerId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));

        // 중복 환불 검증
        Optional<WalletTransaction> existing =
                walletTransactionRepository.findByWallet_IdAndRefTypeAndRefId(
                                buyerWallet.getId(),
                                refType,
                                refId
                        );

        if (existing.isPresent()) {
            WalletTransaction previous = existing.get();

            if (previous.getStatus() != TransactionStatus.IN
                    || previous.getAmount().compareTo(amount) != 0) {
                throw new BusinessException(
                        PaymentErrorCode.REFUND_INFO_MISMATCH
                );
            }

            return PaymentResult.alreadyRefunded(
                    buyerWallet.getBalance()
            );
        }

        // HOLDING 지갑 잠금
        Wallet holdingWallet = walletRepository
                .findByMemberIdForUpdate(memberQuery.getHoldingMemberId())
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));

        // 이미 수납된 배송비는 환불 불가
        if (refType == RefType.SHIPPING_FEE_REFUND
                && walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(
                        holdingWallet.getId(),
                        RefType.SHIPPING_FEE,
                        refId)) {

            throw new BusinessException(
                    PaymentErrorCode.SHIPPING_FEE_ALREADY_COLLECTED);
        }

        if (walletTransactionRepository
                .existsByWallet_IdAndRefTypeAndRefId(
                        holdingWallet.getId(),
                        refType,
                        refId
                )) {
            throw new BusinessException(
                    PaymentErrorCode.REFUND_REFERENCE_CONFLICT
            );
        }

        LocalDateTime now = LocalDateTime.now(); // HOLDING 출금·구매자 입금을 같은 시각으로 기록

        // HOLDING 출금 (잔액 부족 시 INSUFFICIENT_BALANCE 예외)
        holdingWallet.debit(amount);
        walletTransactionRepository.save(
                WalletTransaction.refundOut(
                        holdingWallet, refType, refId, amount, now
                )
        );

        // 구매자 지갑 환불
        buyerWallet.credit(amount);
        walletTransactionRepository.save(
                WalletTransaction.refundIn(
                        buyerWallet, refType, refId, amount, now
                )
        );

        return PaymentResult.refunded(buyerWallet.getBalance());
    }

}