package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.presentation.internal.WithdrawalCheckResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

// member가 탈퇴 전에 부르는 진행 중 주문 확인. DB 오류는 잡지 않고 5xx로 보낸다 (member가 Fail-closed로 막음)
@Service
@RequiredArgsConstructor
public class OrderWithdrawalCheckService {

    // 진행 중: 결제 대기 · 결제 처리 중 · 확인 필요 · 결제 완료 주문 중 아직 끝나지 않은 주문상품(일반 · 반품 요청)이 있는 것
    // 결제 처리 중 · 확인 필요는 결제 결과를 모르는 주문이라 돈이 빠졌을 수 있다 (ADR 0023)
    static final Set<OrderStatus> ACTIVE_ORDER_STATUSES = Set.of(OrderStatus.PAYMENT_PENDING,
            OrderStatus.PAYMENT_PROCESSING, OrderStatus.REVIEW_REQUIRED, OrderStatus.PAID);
    // 진행 중인 주문상품 상태. 수락 대기는 판매자 응답을 기다리는 결제된 주문상품이다 (#284).
    // 취소 중, 확인 필요는 환불 결과를 모르는 주문상품이다 (#282)
    static final Set<OrderItemStatus> ACTIVE_ITEM_STATUSES = Set.of(OrderItemStatus.ACCEPT_PENDING,
            OrderItemStatus.NORMAL, OrderItemStatus.REFUND_REQUESTED,
            OrderItemStatus.CANCEL_PROCESSING, OrderItemStatus.REVIEW_REQUIRED);

    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;

    @Transactional(readOnly = true)
    public WithdrawalCheckResponse check(Long memberId) {
        long buyerCount = ordersRepository.countActiveByBuyerId(memberId, ACTIVE_ORDER_STATUSES, ACTIVE_ITEM_STATUSES);
        long sellerCount = sellerOrderRepository.countActiveBySellerId(memberId, ACTIVE_ORDER_STATUSES, ACTIVE_ITEM_STATUSES);
        return WithdrawalCheckResponse.of(buyerCount, sellerCount);
    }
}
