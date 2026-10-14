package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.common.entity.BaseTimeEntity;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderItemStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "orders_item")
@NoArgsConstructor
@Getter
public class OrdersItem extends BaseTimeEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seller_order_id",  nullable = false)
    private SellerOrder sellerOrder;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String productName;

    @Column(precision = 19, nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderItemStatus status;

    public OrdersItem(SellerOrder sellerOrder, Long productId, String productName, BigDecimal price) {
        this.sellerOrder = sellerOrder;
        this.productId = productId;
        this.productName = productName;
        this.price = price;
        this.status = OrderItemStatus.NORMAL;
    }

    public void confirm() {
        if (this.status != OrderItemStatus.NORMAL) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_CONFIRMABLE);
        }
        this.status = OrderItemStatus.CONFIRMED;
    }

    public void cancel() {
        if (this.status != OrderItemStatus.NORMAL) {
            throw new IllegalStateException("일반 상태의 주문 상품만 구매확정 처리할수 있습니다.");
        }
        this.status = OrderItemStatus.CANCELLED;
    }

}
