package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.PurchaseConfirmedEvent;
import com.justcommit.backend.market.SellerOrderReturnedEvent;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.ReturnRequestStatus;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.domain.SellerOrderSettlementAmounts;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.market.order.infrastructure.kafka.PurchaseConfirmedEventPublisher;
import com.justcommit.backend.market.order.infrastructure.kafka.SellerOrderEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

// 판매자 주문 종료 판정 (#383). 주문상품 하나가 끝난 상태(구매 확정, 반품 거절, 취소 완료, 반품 완료 #384)로 바뀐 뒤 부른다.
// 호출하는 쪽이 주문 → 판매자 주문 잠금을 쥔 트랜잭션 안에서 부른다. 이벤트는 같은 트랜잭션의 Outbox 행이다.
// 판매자 주문이 끝나면 정산 이벤트를 한 번 발행하고 confirmedAt을 종료 기록으로 남긴다. confirmedAt이 있으면 다시 발행하지 않는다
// - 구매 확정 1개 이상: PurchaseConfirmed (판매액 = 구매 확정 상품 합, 판매자 부담금 = 반품 완료된 반품 배송비 합)
// - 구매 확정 0개, 반품 완료 1개 이상(전부 반품): SellerOrderReturned (판매자 부담금 = 반품 배송비 합 + 원래 배송비)
// - 그 밖(모두 취소): 발행하지 않는다
@Component
@RequiredArgsConstructor
public class SellerOrderCompletion {

    private final ReturnRequestRepository returnRequestRepository;
    private final PurchaseConfirmedEventPublisher purchaseConfirmedEventPublisher;
    private final SellerOrderEventPublisher sellerOrderEventPublisher;

    // 판매자 주문이 끝났으면 정산 이벤트를 발행하고, 주문의 모든 판매자 주문이 끝났으면 주문 상태를 바꾼다
    public void complete(Orders order, SellerOrder sellerOrder) {
        requestSettlement(sellerOrder);
        updateOrderStatus(order);
    }

    private void requestSettlement(SellerOrder sellerOrder) {
        if (sellerOrder.getConfirmedAt() != null || hasUnfinished(sellerOrder.getItems())) {
            return;
        }
        List<OrdersItem> confirmed = itemsIn(sellerOrder, OrderItemStatus.CONFIRMED);
        List<OrdersItem> returned = itemsIn(sellerOrder, OrderItemStatus.RETURNED);
        if (confirmed.isEmpty() && returned.isEmpty()) {
            return; // 모두 취소: 미정산 대상
        }

        // 반품 완료로 바뀐 뒤 다시 계산하지 않았어도 구매 확정 상품 합이 되도록 여기서 다시 계산한다
        sellerOrder.calculateSettlementBaseAmount();
        BigDecimal returnShippingFees =
                returnRequestRepository.sumReturnShippingFee(sellerOrder.getId(), ReturnRequestStatus.COMPLETED);
        sellerOrder.confirmed();

        // 금액은 정산 대상 조회 내부 API와 같은 계산을 쓴다 (#657)
        SellerOrderSettlementAmounts amounts = SellerOrderSettlementAmounts.of(!confirmed.isEmpty(),
                sellerOrder.getSettlementBaseAmount(), sellerOrder.getShippingFee(), returnShippingFees);
        if (amounts.type() == SellerOrderSettlementAmounts.Type.PURCHASE_CONFIRMED) {
            purchaseConfirmedEventPublisher.publishAfterCommit(new PurchaseConfirmedEvent(
                    sellerOrder.getId(), sellerOrder.getSellerId(), amounts.saleAmount(), amounts.shippingFee(),
                    amounts.sellerBurdenAmount(), sellerOrder.getConfirmedAt(), itemSummary(confirmed)));
            return;
        }
        sellerOrderEventPublisher.publishAfterCommit(new SellerOrderReturnedEvent(
                sellerOrder.getId(), sellerOrder.getSellerId(), amounts.sellerBurdenAmount(),
                sellerOrder.getConfirmedAt(), itemSummary(returned)));
    }

    // 모든 판매자 주문이 끝났을 때만 바꾼다. 구매 확정이나 반품 완료가 하나라도 있으면(정산 이벤트가 나감) 완료,
    // 없으면(전부 수거 전 취소) 취소. 정산 대상인데 아직 종료 기록이 없는 판매자 주문이 있으면 기다린다
    private void updateOrderStatus(Orders order) {
        List<SellerOrder> sellerOrders = order.getSellerOrders();
        if (sellerOrders.stream().anyMatch(s -> hasUnfinished(s.getItems()))) {
            return;
        }
        if (sellerOrders.stream().anyMatch(s -> s.getConfirmedAt() == null && isSettlementTarget(s))) {
            return;
        }
        if (sellerOrders.stream().anyMatch(SellerOrderCompletion::isSettlementTarget)) {
            order.confirm();
        } else {
            order.cancel();
        }
    }

    // 아직 끝나지 않은 주문상품: 수락 대기, 일반, 환불 결과를 기다리는 취소 중, 확인 필요 (#282, #284),
    // 반품 진행 중(신청, 배송 중, 환불 중, #382). 구매 확정, 취소 완료, 반품 완료는 끝난 상태다.
    // 취소 중은 택배 취소가 거절되면 일반으로 돌아오므로 판매자 주문, 주문을 끝내지 않는다
    static boolean isUnfinished(OrdersItem item) {
        return switch (item.getStatus()) {
            case ACCEPT_PENDING, NORMAL, CANCEL_PROCESSING, REVIEW_REQUIRED,
                 RETURN_REQUESTED, RETURN_IN_TRANSIT, RETURN_PROCESSING -> true;
            default -> false;
        };
    }

    private static boolean hasUnfinished(List<OrdersItem> items) {
        return items.stream().anyMatch(SellerOrderCompletion::isUnfinished);
    }

    // 구매 확정이나 반품 완료 상품이 있어 정산 이벤트를 발행하는 판매자 주문
    private static boolean isSettlementTarget(SellerOrder sellerOrder) {
        return sellerOrder.getItems().stream().anyMatch(i -> i.getStatus() == OrderItemStatus.CONFIRMED
                || i.getStatus() == OrderItemStatus.RETURNED);
    }

    private static List<OrdersItem> itemsIn(SellerOrder sellerOrder, OrderItemStatus status) {
        return sellerOrder.getItems().stream().filter(i -> i.getStatus() == status).toList();
    }

    private static String itemSummary(List<OrdersItem> items) {
        List<String> names = items.stream().map(OrdersItem::getProductName).toList();
        return names.size() == 1 ? names.getFirst() : names.getFirst() + " 외 " + (names.size() - 1) + "건";
    }
}
