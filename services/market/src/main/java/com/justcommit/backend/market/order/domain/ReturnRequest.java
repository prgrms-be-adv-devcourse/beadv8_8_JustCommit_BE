package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 반품 신청 (#382). 주문상품 하나에 한 번만 (order_item_id 유니크). 거절, 완료된 신청도 지우지 않는다
@Entity
@Table(name = "return_request")
@NoArgsConstructor
@Getter
public class ReturnRequest extends BaseTimeEntity {

    public static final int REASON_DETAIL_MAX_LENGTH = 500;

    @Column(nullable = false, unique = true)
    private Long orderItemId;

    @Column(nullable = false)
    private Long sellerOrderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReturnReason reason;

    // 직접 작성 내용. 기타면 필수, 그 밖의 사유는 선택
    @Column(length = REASON_DETAIL_MAX_LENGTH)
    private String reasonDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnRequestStatus status;

    // 승인, 거절한 쪽 (#383). 응답 전이면 비어 있다
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReturnDecidedBy decidedBy;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime decidedAt;

    // 승인 때 남기는 반품 배송비 (#383)
    @Column(precision = 19)
    private BigDecimal returnShippingFee;

    // 반품 배송 (#383)
    private Long shipmentId;

    public ReturnRequest(Long orderItemId, Long sellerOrderId, ReturnReason reason, String reasonDetail,
                         LocalDateTime requestedAt) {
        this.orderItemId = orderItemId;
        this.sellerOrderId = sellerOrderId;
        this.reason = reason;
        this.reasonDetail = reasonDetail;
        this.status = ReturnRequestStatus.REQUESTED;
        this.requestedAt = requestedAt;
    }
}
