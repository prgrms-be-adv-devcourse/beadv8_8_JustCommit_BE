package com.justcommit.backend.payment;

import java.math.BigDecimal;

public interface PaymentUseCase {

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
    PaymentResult pay(Long orderId, Long buyerId, BigDecimal amount);


    /**
     * 취소된 주문상품 금액을 HOLDING에서 구매자 지갑으로 환불한다.
     *
     * <p>같은 주문상품은 한 번만 환불된다. 실패는 결과 상태가 아니라 예외로 알리며,
     * 호출 측은 예외를 잡지 않고 전파해 주문 취소까지 함께 롤백한다.</p>
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
    PaymentResult refundCancelledItem(Long orderItemId, Long buyerId, BigDecimal amount);


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
     *         {@link #refundCancelledItem(Long, Long, BigDecimal)}과 같은 조건
     */
    PaymentResult refundShippingFee(Long sellerOrderId, Long buyerId, BigDecimal amount);



    void distributeSettlement(
            Long settlementId,
            Long sellerId,
            BigDecimal payoutAmount,
            BigDecimal fee
    );

    /**
     * 택배가 수거된 판매자 주문의 배송비를 HOLDING에서 REVENUE로 옮긴다.
     *
     * <p>배송비는 정산(구매확정)과 별개로, 수거되어 더 이상 취소할 수 없게 된 시점에 매출로 잡는다.
     * 같은 판매자 주문은 한 번만 처리되며, 다시 호출되면 아무것도 하지 않는다(Kafka 중복 수신 대비).</p>
     *
     * <p>수거 전 취소로 배송비가 이미 구매자에게 환불된 판매자 주문은 건너뛴다.</p>
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
    void collectShippingFee(Long sellerOrderId, BigDecimal shippingFee);

}
