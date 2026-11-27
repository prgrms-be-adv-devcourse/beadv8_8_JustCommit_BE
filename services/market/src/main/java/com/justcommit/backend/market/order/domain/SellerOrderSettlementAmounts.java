package com.justcommit.backend.market.order.domain;

import java.math.BigDecimal;
import java.util.Objects;

// 끝난 판매자 주문의 정산 종류와 금액 (#657). 정산 이벤트(SellerOrderCompletion)와 정산 대상 조회 내부 API가 같은 계산을 쓴다
// - PURCHASE_CONFIRMED: 판매액 = 정산 기준액(구매 확정 상품 합), 배송비 = 원래 배송비, 판매자 부담금 = 반품 완료된 반품 배송비 합
// - RETURNED(전부 반품): 판매액 0, 배송비 0(원래 배송비는 구매자에게 환불됨), 판매자 부담금 = 반품 배송비 합 + 원래 배송비
public record SellerOrderSettlementAmounts(Type type, BigDecimal saleAmount, BigDecimal shippingFee,
                                    BigDecimal sellerBurdenAmount) {

    public enum Type {
        PURCHASE_CONFIRMED,
        RETURNED
    }

    public SellerOrderSettlementAmounts {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(saleAmount, "saleAmount");
        Objects.requireNonNull(shippingFee, "shippingFee");
        Objects.requireNonNull(sellerBurdenAmount, "sellerBurdenAmount");
    }

    // hasConfirmed: 구매 확정 주문상품이 1개 이상인지. 모두 취소인 판매자 주문은 정산 대상이 아니라 부르지 않는다
    public static SellerOrderSettlementAmounts of(boolean hasConfirmed, BigDecimal settlementBaseAmount, BigDecimal shippingFee,
                                           BigDecimal returnShippingFees) {
        BigDecimal returnFees = Objects.requireNonNullElse(returnShippingFees, BigDecimal.ZERO);
        if (hasConfirmed) {
            return new SellerOrderSettlementAmounts(Type.PURCHASE_CONFIRMED, settlementBaseAmount, shippingFee, returnFees);
        }
        return new SellerOrderSettlementAmounts(Type.RETURNED, BigDecimal.ZERO, BigDecimal.ZERO, returnFees.add(shippingFee));
    }
}
