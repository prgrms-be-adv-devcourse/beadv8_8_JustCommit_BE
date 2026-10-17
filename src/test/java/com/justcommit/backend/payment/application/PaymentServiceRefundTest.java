package com.justcommit.backend.payment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.payment.PaymentResult;
import com.justcommit.backend.payment.PaymentStatus;
import com.justcommit.backend.payment.domain.RefType;
import com.justcommit.backend.payment.domain.TransactionStatus;
import com.justcommit.backend.payment.domain.Wallet;
import com.justcommit.backend.payment.domain.WalletTransaction;
import com.justcommit.backend.payment.domain.exception.PaymentErrorCode;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceRefundTest {

    private static final Long ORDER_ITEM_ID = 100L;
    private static final Long SELLER_ORDER_ID = 200L;

    private static final Long BUYER_ID = 10L;
    private static final Long BUYER_WALLET_ID = 1L;

    private static final Long HOLDING_ID = 999L;
    private static final Long HOLDING_WALLET_ID = 2L;

    @Mock WalletRepository walletRepository;
    @Mock WalletTransactionRepository walletTransactionRepository;
    @Mock MemberQuery memberQuery;
    @InjectMocks PaymentService paymentService;

    // ===== refundCancelledItem =====

    @Test
    @DisplayName("상품 취소 환불 시 HOLDING에서 차감하고 구매자 지갑에 적립한 뒤 REFUNDED를 반환한다")
    void refundCancelledItemMovesMoneyFromHoldingToBuyer() {
        Wallet buyerWallet = buyerWallet("5000");
        Wallet holdingWallet = holdingWallet("10000");
        stubBuyerWallet(buyerWallet, RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID);
        stubHoldingWallet(holdingWallet);
        stubHoldingRefundExists(RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID, false);

        PaymentResult result = paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(result.balanceAfter()).isEqualByComparingTo("8000");
        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("8000");
        assertThat(holdingWallet.getBalance()).isEqualByComparingTo("7000");
    }

    @Test
    @DisplayName("상품 취소 환불 시 HOLDING 출금과 구매자 입금 내역이 주문상품 ID로 기록된다")
    void refundCancelledItemRecordsHoldingOutAndBuyerIn() {
        stubBuyerWallet(buyerWallet("5000"), RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID);
        stubHoldingWallet(holdingWallet("10000"));
        stubHoldingRefundExists(RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID, false);

        paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("3000"));

        List<WalletTransaction> saved = capturedTransactions(2);
        assertThat(saved)
                .extracting(t -> t.getWallet().getId(), WalletTransaction::getRefType, WalletTransaction::getRefId,
                        WalletTransaction::getStatus, t -> t.getAmount().toPlainString(),
                        t -> t.getBalanceAfter().toPlainString())
                .containsExactly(
                        tuple(HOLDING_WALLET_ID, RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID,
                                TransactionStatus.OUT, "3000", "7000"),
                        tuple(BUYER_WALLET_ID, RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID,
                                TransactionStatus.IN, "3000", "8000"));
        assertThat(saved).allSatisfy(t -> assertThat(t.getRefOccurredTime()).isNotNull());
    }

    @Test
    @DisplayName("이미 환불된 주문상품이면 잔액을 바꾸지 않고 ALREADY_REFUNDED를 반환한다")
    void refundCancelledItemIsIdempotent() {
        Wallet buyerWallet = buyerWallet("8000");
        stubBuyerWalletAlreadyRefunded(buyerWallet, RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID, "3000");

        PaymentResult result = paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.ALREADY_REFUNDED);
        assertThat(result.balanceAfter()).isEqualByComparingTo("8000");
        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("8000");
        verify(walletTransactionRepository, never()).save(any());
        verifyNoInteractions(memberQuery); // HOLDING 지갑은 잠그지 않는다
    }

    // ===== refundShippingFee =====

    @Test
    @DisplayName("배송비 환불은 판매자 주문 ID와 SHIPPING_FEE_REFUND로 기록된다")
    void refundShippingFeeRecordsWithSellerOrderId() {
        Wallet buyerWallet = buyerWallet("0");
        Wallet holdingWallet = holdingWallet("10000");
        stubBuyerWallet(buyerWallet, RefType.SHIPPING_FEE_REFUND, SELLER_ORDER_ID);
        stubHoldingWallet(holdingWallet);
        stubHoldingRefundExists(RefType.SHIPPING_FEE_REFUND, SELLER_ORDER_ID, false);
        stubShippingFeeCollected(SELLER_ORDER_ID, false);

        PaymentResult result = paymentService.refundShippingFee(SELLER_ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("3000");
        assertThat(holdingWallet.getBalance()).isEqualByComparingTo("7000");
        assertThat(capturedTransactions(2))
                .extracting(WalletTransaction::getRefType, WalletTransaction::getRefId, WalletTransaction::getStatus)
                .containsExactly(
                        tuple(RefType.SHIPPING_FEE_REFUND, SELLER_ORDER_ID, TransactionStatus.OUT),
                        tuple(RefType.SHIPPING_FEE_REFUND, SELLER_ORDER_ID, TransactionStatus.IN));
    }

    @Test
    @DisplayName("상품 환불과 배송비 환불은 같은 ID여도 중복 판정 키(RefType)가 달라 서로 독립적이다")
    void shippingFeeRefundUsesItsOwnDuplicateKey() {
        Long sameId = 300L;
        stubBuyerWallet(buyerWallet("0"), RefType.SHIPPING_FEE_REFUND, sameId);
        stubHoldingWallet(holdingWallet("10000"));
        stubHoldingRefundExists(RefType.SHIPPING_FEE_REFUND, sameId, false);
        stubShippingFeeCollected(sameId, false);

        PaymentResult result = paymentService.refundShippingFee(sameId, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.REFUNDED);
        verify(walletTransactionRepository, never())
                .findByWallet_IdAndRefTypeAndRefId(BUYER_WALLET_ID, RefType.ORDER_ITEM_CANCEL_REFUND, sameId);
    }

    @Test
    @DisplayName("이미 환불된 배송비면 잔액을 바꾸지 않고 ALREADY_REFUNDED를 반환한다")
    void refundShippingFeeIsIdempotent() {
        stubBuyerWalletAlreadyRefunded(buyerWallet("3000"), RefType.SHIPPING_FEE_REFUND, SELLER_ORDER_ID, "3000");

        PaymentResult result = paymentService.refundShippingFee(SELLER_ORDER_ID, BUYER_ID, new BigDecimal("3000"));

        assertThat(result.status()).isEqualTo(PaymentStatus.ALREADY_REFUNDED);
        verify(walletTransactionRepository, never()).save(any());
        verifyNoInteractions(memberQuery);
    }

    // ===== 기존 내역과 다른 요청 =====

    @Test
    @DisplayName("같은 주문상품에 다른 금액으로 환불을 요청하면 REFUND_INFO_MISMATCH 예외")
    void throwsWhenAmountDiffersFromPreviousRefund() {
        Wallet buyerWallet = buyerWallet("8000");
        stubBuyerWalletAlreadyRefunded(buyerWallet, RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID, "3000");

        assertThatThrownBy(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("5000")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_INFO_MISMATCH);

        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("8000");
        verify(walletTransactionRepository, never()).save(any());
        verifyNoInteractions(memberQuery);
    }

    @Test
    @DisplayName("HOLDING에 같은 환불 건 출금 내역이 이미 있으면(다른 구매자에게 처리됨) REFUND_REFERENCE_CONFLICT 예외")
    void throwsWhenRefundAlreadyProcessedForAnotherBuyer() {
        Wallet buyerWallet = buyerWallet("5000");
        Wallet holdingWallet = holdingWallet("10000");
        stubBuyerWallet(buyerWallet, RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID);
        stubHoldingWallet(holdingWallet);
        stubHoldingRefundExists(RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID, true);

        assertThatThrownBy(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("3000")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.REFUND_REFERENCE_CONFLICT);

        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("5000");
        assertThat(holdingWallet.getBalance()).isEqualByComparingTo("10000");
        verify(walletTransactionRepository, never()).save(any());
    }

    // ===== 실패: 예외로 던져 호출 측 트랜잭션이 롤백되게 한다 =====

    @Test
    @DisplayName("구매자 지갑이 없으면 WALLET_NOT_FOUND 예외")
    void throwsWhenBuyerWalletMissing() {
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("3000")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.WALLET_NOT_FOUND);

        verifyNoInteractions(walletTransactionRepository, memberQuery);
    }

    @Test
    @DisplayName("HOLDING 지갑이 없으면 WALLET_NOT_FOUND 예외, 구매자 잔액은 그대로")
    void throwsWhenHoldingWalletMissing() {
        Wallet buyerWallet = buyerWallet("5000");
        stubBuyerWallet(buyerWallet, RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID);
        when(memberQuery.getHoldingMemberId()).thenReturn(HOLDING_ID);
        when(walletRepository.findByMemberIdForUpdate(HOLDING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("3000")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.WALLET_NOT_FOUND);

        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("5000");
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("HOLDING 잔액이 부족하면 INSUFFICIENT_BALANCE 예외, 잔액과 내역 모두 변하지 않는다")
    void throwsWhenHoldingInsufficient() {
        Wallet buyerWallet = buyerWallet("5000");
        Wallet holdingWallet = holdingWallet("1000");
        stubBuyerWallet(buyerWallet, RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID);
        stubHoldingWallet(holdingWallet);
        stubHoldingRefundExists(RefType.ORDER_ITEM_CANCEL_REFUND, ORDER_ITEM_ID, false);

        assertThatThrownBy(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("3000")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INSUFFICIENT_BALANCE);

        assertThat(buyerWallet.getBalance()).isEqualByComparingTo("5000");
        assertThat(holdingWallet.getBalance()).isEqualByComparingTo("1000");
        verify(walletTransactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("인자가 null이거나 금액이 0 이하이면 INVALID_AMOUNT 예외")
    void rejectsInvalidArguments() {
        assertInvalid(() -> paymentService.refundCancelledItem(null, BUYER_ID, new BigDecimal("3000")));
        assertInvalid(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, null, new BigDecimal("3000")));
        assertInvalid(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, null));
        assertInvalid(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, BigDecimal.ZERO));
        assertInvalid(() -> paymentService.refundCancelledItem(ORDER_ITEM_ID, BUYER_ID, new BigDecimal("-1")));

        assertInvalid(() -> paymentService.refundShippingFee(null, BUYER_ID, new BigDecimal("3000")));
        assertInvalid(() -> paymentService.refundShippingFee(SELLER_ORDER_ID, null, new BigDecimal("3000")));
        assertInvalid(() -> paymentService.refundShippingFee(SELLER_ORDER_ID, BUYER_ID, null));
        assertInvalid(() -> paymentService.refundShippingFee(SELLER_ORDER_ID, BUYER_ID, BigDecimal.ZERO));

        verifyNoInteractions(walletRepository, walletTransactionRepository, memberQuery);
    }


    @Test
    @DisplayName("이미 수납된 배송비는 환불할 수 없다")
    void rejectsRefundWhenShippingFeeAlreadyCollected() {
        Wallet buyerWallet = buyerWallet("0");
        Wallet holdingWallet = holdingWallet("10000");

        stubBuyerWallet(
                buyerWallet,
                RefType.SHIPPING_FEE_REFUND,
                SELLER_ORDER_ID
        );
        stubHoldingWallet(holdingWallet);

        // 해당 판매자 주문의 배송비가 이미 수납됨
        when(walletTransactionRepository
                .existsByWallet_IdAndRefTypeAndRefId(
                        HOLDING_WALLET_ID,
                        RefType.SHIPPING_FEE,
                        SELLER_ORDER_ID
                )).thenReturn(true);

        assertThatThrownBy(() ->
                paymentService.refundShippingFee(
                        SELLER_ORDER_ID,
                        BUYER_ID,
                        new BigDecimal("3000")
                ))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue(
                        "errorCode",
                        PaymentErrorCode.SHIPPING_FEE_ALREADY_COLLECTED
                );

        // 환불이 발생하지 않았는지 확인
        assertThat(buyerWallet.getBalance())
                .isEqualByComparingTo("0");
        assertThat(holdingWallet.getBalance())
                .isEqualByComparingTo("10000");

        verify(walletTransactionRepository, never()).save(any());
    }

    // ===== helpers =====

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PaymentErrorCode.INVALID_AMOUNT);
    }

    // 구매자 지갑 조회 + 기존 환불 내역 없음
    private void stubBuyerWallet(Wallet buyerWallet, RefType refType, Long refId) {
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.of(buyerWallet));
        when(walletTransactionRepository.findByWallet_IdAndRefTypeAndRefId(BUYER_WALLET_ID, refType, refId))
                .thenReturn(Optional.empty());
    }

    // 구매자 지갑 조회 + 기존 환불 내역 있음
    private void stubBuyerWalletAlreadyRefunded(Wallet buyerWallet, RefType refType, Long refId, String refundedAmount) {
        when(walletRepository.findByMemberIdForUpdate(BUYER_ID)).thenReturn(Optional.of(buyerWallet));
        WalletTransaction previous = WalletTransaction.refundIn(
                buyerWallet, refType, refId, new BigDecimal(refundedAmount), LocalDateTime.now());
        when(walletTransactionRepository.findByWallet_IdAndRefTypeAndRefId(BUYER_WALLET_ID, refType, refId))
                .thenReturn(Optional.of(previous));
    }

    // HOLDING 회원 ID 조회 + HOLDING 지갑 조회
    private void stubHoldingWallet(Wallet holdingWallet) {
        when(memberQuery.getHoldingMemberId()).thenReturn(HOLDING_ID);
        when(walletRepository.findByMemberIdForUpdate(HOLDING_ID)).thenReturn(Optional.of(holdingWallet));
    }

    // HOLDING 지갑에 같은 환불 건 출금 내역이 있는지
    private void stubHoldingRefundExists(RefType refType, Long refId, boolean exists) {
        when(walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(HOLDING_WALLET_ID, refType, refId))
                .thenReturn(exists);
    }

    private void stubShippingFeeCollected(Long sellerOrderId, boolean collected) {
        when(walletTransactionRepository.existsByWallet_IdAndRefTypeAndRefId(
                HOLDING_WALLET_ID, RefType.SHIPPING_FEE, sellerOrderId))
                .thenReturn(collected);
    }

    private List<WalletTransaction> capturedTransactions(int expectedCount) {
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository, times(expectedCount)).save(captor.capture());
        return captor.getAllValues();
    }

    private Wallet buyerWallet(String balance) {
        return wallet(BUYER_ID, BUYER_WALLET_ID, balance);
    }

    private Wallet holdingWallet(String balance) {
        return wallet(HOLDING_ID, HOLDING_WALLET_ID, balance);
    }

    private Wallet wallet(Long memberId, Long walletId, String balance) {
        Wallet wallet = new Wallet(memberId);
        ReflectionTestUtils.setField(wallet, "id", walletId);
        if (new BigDecimal(balance).signum() > 0) {
            wallet.credit(new BigDecimal(balance));
        }
        return wallet;
    }
}