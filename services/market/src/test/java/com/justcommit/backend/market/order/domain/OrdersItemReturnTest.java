package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.market.OrderItemStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrdersItemReturnTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

    @Test
    @DisplayName("반품 기간은 배송 완료 + 3일 직전까지. 정확히 3일이 되는 순간부터는 자동 구매 확정 쪽이다")
    void returnPeriodBoundaryMatchesAutoConfirmation() {
        LocalDateTime cutoff = NOW.minus(OrdersItem.RETURN_PERIOD);

        assertThat(OrdersItem.isWithinReturnPeriod(cutoff.plusNanos(1), NOW)).isTrue();
        assertThat(OrdersItem.isWithinReturnPeriod(cutoff, NOW)).isFalse();
        assertThat(OrdersItem.isWithinReturnPeriod(cutoff.minusNanos(1), NOW)).isFalse();
        // 자동 구매 확정 후보 조건(delivered_at <= cutoff)과 정확히 반대
        for (LocalDateTime deliveredAt : List.of(cutoff.minusSeconds(1), cutoff, cutoff.plusSeconds(1))) {
            boolean autoConfirmable = !deliveredAt.isAfter(cutoff);
            assertThat(OrdersItem.isWithinReturnPeriod(deliveredAt, NOW)).isNotEqualTo(autoConfirmable);
        }
    }

    @Test
    @DisplayName("일반 주문상품만 반품 신청으로 바뀐다")
    void requestReturnOnlyFromNormal() {
        OrdersItem item = item();
        item.requestReturn();
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.RETURN_REQUESTED);
        assertThat(item.isInReturn()).isTrue();

        OrdersItem confirmed = item();
        ReflectionTestUtils.setField(confirmed, "status", OrderItemStatus.CONFIRMED);
        assertThatThrownBy(confirmed::requestReturn).isInstanceOf(IllegalStateException.class);
        assertThat(confirmed.isInReturn()).isFalse();
    }

    @Test
    @DisplayName("정산 기준액은 반품 신청, 배송 중, 환불 중을 포함하고 반품 완료는 뺀다")
    void settlementBaseAmountByReturnStatus() {
        Orders order = new Orders("ORD-1", 10L, "구매자", "01012345678", "12345", "서울시", null, 4);
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        OrdersItem requested = new OrdersItem(sellerOrder, 1L, "a", new BigDecimal("1000"));
        OrdersItem inTransit = new OrdersItem(sellerOrder, 2L, "b", new BigDecimal("2000"));
        OrdersItem processing = new OrdersItem(sellerOrder, 3L, "c", new BigDecimal("4000"));
        OrdersItem returned = new OrdersItem(sellerOrder, 4L, "d", new BigDecimal("8000"));
        sellerOrder.addItems(List.of(requested, inTransit, processing, returned));
        ReflectionTestUtils.setField(requested, "status", OrderItemStatus.RETURN_REQUESTED);
        ReflectionTestUtils.setField(inTransit, "status", OrderItemStatus.RETURN_IN_TRANSIT);
        ReflectionTestUtils.setField(processing, "status", OrderItemStatus.RETURN_PROCESSING);
        ReflectionTestUtils.setField(returned, "status", OrderItemStatus.RETURNED);

        sellerOrder.calculateSettlementBaseAmount();

        assertThat(sellerOrder.getSettlementBaseAmount()).isEqualByComparingTo("7000");
    }

    private static OrdersItem item() {
        Orders order = new Orders("ORD-1", 10L, "구매자", "01012345678", "12345", "서울시", null, 1);
        return new OrdersItem(new SellerOrder(order, 20L), 1L, "몬스테라", new BigDecimal("10000"));
    }
}
