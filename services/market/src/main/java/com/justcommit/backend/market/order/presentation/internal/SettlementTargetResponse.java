package com.justcommit.backend.market.order.presentation.internal;

import com.justcommit.backend.market.order.application.SettlementTargetQueryService.SettlementTarget;
import com.justcommit.backend.market.order.application.SettlementTargetQueryService.SettlementTargetPage;
import com.justcommit.backend.market.order.domain.SellerOrderSettlementAmounts;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 정산 대상 조회 응답 (#657). 금액은 정산 이벤트(PurchaseConfirmed, SellerOrderReturned)에 넣은 값과 같다.
// items는 (confirmedAt, sellerOrderId) 오름차순. nextCursor: 다음 요청의 cursor 문자열("{confirmedAt}_{sellerOrderId}"), 마지막 페이지면 null
public record SettlementTargetResponse(List<Item> items, String nextCursor) {

    // type: PURCHASE_CONFIRMED(구매 확정 1개 이상) 또는 RETURNED(전부 반품). RETURNED는 saleAmount, shippingFee가 0
    public record Item(
            Long sellerOrderId,
            Long sellerId,
            SellerOrderSettlementAmounts.Type type,
            BigDecimal saleAmount,
            BigDecimal shippingFee,
            BigDecimal sellerBurdenAmount,
            LocalDateTime confirmedAt
    ) {

        static Item from(SettlementTarget target) {
            SellerOrderSettlementAmounts s = target.amounts();
            return new Item(target.sellerOrderId(), target.sellerId(), s.type(), s.saleAmount(), s.shippingFee(),
                    s.sellerBurdenAmount(), target.confirmedAt());
        }
    }

    public static SettlementTargetResponse from(SettlementTargetPage page) {
        return new SettlementTargetResponse(page.items().stream().map(Item::from).toList(),
                page.nextCursor() == null ? null : page.nextCursor().encode());
    }
}
