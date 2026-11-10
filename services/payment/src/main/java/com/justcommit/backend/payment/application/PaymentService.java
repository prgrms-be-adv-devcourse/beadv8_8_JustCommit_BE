package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.payment.domain.WalletType;
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
public class PaymentService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final WalletService walletService;

    /**
     * 주문 결제 금액만큼 구매자의 예치금을 차감한다.
     *
     * <p>잔액 부족, 지갑 없음, 중복 요청은 예외가 아니라 결과 상태로 돌려준다.</p>
     *
     * @param orderId 주문 ID. 같은 주문은 한 번만 차감된다
     * @param buyerId 구매자 회원 ID
     * @param amount  결제 금액 (0보다 커야 함)
     * @return {@link PaymentStatus#PAID}, {@link PaymentStatus#ALREADY_PAID},
     *         {@link PaymentStatus#INSUFFICIENT_BALANCE}, {@link PaymentStatus#WALLET_NOT_FOUND} 중 하나
     * @throws com.justcommit.backend.common.exception.BusinessException 인자가 null이거나 금액이 0 이하일 때
     */
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


        Wallet holdingWallet = walletRepository
                .findByWalletTypeForUpdate(WalletType.HOLDING)
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

    /**
     * 정산금을 HOLDING에서 판매자 지갑으로, 수수료를 REVENUE로 옮긴다.
     *
     * <p>같은 정산은 한 번만 지급되며, 다시 호출되면 아무것도 하지 않는다.
     * 판매자 지갑이 없으면 만든다.</p>
     *
     * @param settlementId 정산 ID. 중복 지급 판정 기준
     * @param sellerId     판매자 회원 ID
     * @param payoutAmount 판매자 정산금 (0보다 커야 함)
     * @param fee          수수료 (0 이상)
     * @throws com.justcommit.backend.common.exception.BusinessException 실패하면 4xx 응답으로 나가고, 호출 측은 같은 키로 다시 호출한다
     *         <ul>
     *           <li>INVALID_AMOUNT: 인자가 null이거나 정산금이 0 이하, 수수료가 0 미만</li>
     *           <li>WALLET_NOT_FOUND: HOLDING 또는 REVENUE 지갑 없음</li>
     *           <li>INSUFFICIENT_BALANCE: HOLDING 잔액 부족</li>
     *         </ul>
     */
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

        // 판매자 지갑 잠금
        Wallet sellerWallet = walletRepository
                .findByMemberIdForUpdate(sellerId)
                .orElseGet(() -> {
                    walletService.createIfAbsent(sellerId);
                    return walletRepository.findByMemberIdForUpdate(sellerId)
                            .orElseThrow(() -> new BusinessException(PaymentErrorCode.WALLET_NOT_FOUND));
                });

        // 중복 정산 방지
        if (walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(
                sellerWallet.getId(), RefType.SETTLEMENT, settlementId)) {
            return;
        }

        // HOLDING 지갑 잠금
        Wallet holdingWallet = walletRepository
                .findByWalletTypeForUpdate(WalletType.HOLDING)
                .orElseThrow(() -> new BusinessException(
                        PaymentErrorCode.WALLET_NOT_FOUND
                ));

        // REVENUE 지갑 잠금
        Wallet revenueWallet = walletRepository
                .findByWalletTypeForUpdate(WalletType.REVENUE)
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
     *
     * <p>배송비는 정산(구매확정)과 별개로, 수거되어 더 이상 취소할 수 없게 된 시점에 매출로 잡는다.
     * 같은 판매자 주문은 한 번만 처리되며, 다시 호출되면 아무것도 하지 않는다.
     * 수거 전 취소로 배송비가 이미 구매자에게 환불된 판매자 주문은 건너뛴다.
     * 시스템 지갑만 잠그며, 순서는 distributeSettlement와 같은 HOLDING -> REVENUE.</p>
     *
     * @param sellerOrderId 판매자 주문 ID. 중복 처리 판정 기준
     * @param shippingFee   배송비
     * @throws com.justcommit.backend.common.exception.BusinessException
     *         <ul>
     *           <li>INVALID_AMOUNT: sellerOrderId가 null이거나 배송비가 null 또는 0 이하</li>
     *           <li>WALLET_NOT_FOUND: HOLDING 또는 REVENUE 지갑 없음</li>
     *           <li>INSUFFICIENT_BALANCE: HOLDING 잔액 부족</li>
     *         </ul>
     */
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
                .findByWalletTypeForUpdate(WalletType.HOLDING)
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
                .findByWalletTypeForUpdate(WalletType.REVENUE)
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
     *
     * <p>같은 주문상품은 한 번만 환불된다. 실패는 결과 상태가 아니라 예외로 알리며,
     * 예외는 4xx 응답으로 나가고 호출 측은 같은 키로 다시 호출한다.</p>
     *
     * @param orderItemId 취소된 주문상품 ID. 중복 환불 판정 기준
     * @param buyerId     구매자 회원 ID
     * @param amount      환불 금액 (0보다 커야 함)
     * @return {@link PaymentStatus#REFUNDED} 또는 {@link PaymentStatus#ALREADY_REFUNDED}.
     *         둘 다 환불이 완료된 상태이므로 호출 측에서 분기할 필요는 없다
     * @throws com.justcommit.backend.common.exception.BusinessException
     *         <ul>
     *           <li>INVALID_AMOUNT: 인자가 null이거나 금액이 0 이하</li>
     *           <li>WALLET_NOT_FOUND: 구매자 또는 HOLDING 지갑 없음</li>
     *           <li>INSUFFICIENT_BALANCE: HOLDING 잔액 부족</li>
     *           <li>REFUND_INFO_MISMATCH: 같은 주문상품의 기존 환불 금액과 요청 금액이 다름</li>
     *           <li>REFUND_REFERENCE_CONFLICT: 같은 주문상품이 다른 구매자에게 이미 환불됨</li>
     *         </ul>
     */
    @Transactional
    public PaymentResult refundCancelledItem(Long orderItemId, Long buyerId, BigDecimal amount) {
        return refundFromHolding(RefType.ORDER_ITEM_CANCEL_REFUND, orderItemId, buyerId, amount);
    }

    /**
     * 판매자 주문의 상품이 모두 취소됐을 때 배송비를 HOLDING에서 구매자 지갑으로 환불한다.
     *
     * <p>같은 판매자 주문은 한 번만 환불된다. 반환값과 예외 계약은
     * {@link #refundCancelledItem(Long, Long, BigDecimal)}과 같으며, 중복 판정 기준은 판매자 주문 ID다.</p>
     *
     * @param sellerOrderId 판매자 주문 ID. 중복 환불 판정 기준
     * @param buyerId       구매자 회원 ID
     * @param amount        환불할 배송비 (0보다 커야 함)
     * @return {@link PaymentStatus#REFUNDED} 또는 {@link PaymentStatus#ALREADY_REFUNDED}
     * @throws com.justcommit.backend.common.exception.BusinessException
     *         {@link #refundCancelledItem(Long, Long, BigDecimal)}과 같은 조건,
     *         이미 수납된 배송비면 SHIPPING_FEE_ALREADY_COLLECTED
     */
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
                .findByWalletTypeForUpdate(WalletType.HOLDING)
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