package com.justcommit.backend.market.order.domain;

import com.justcommit.backend.market.ReturnDecidedBy;
import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

// 반품 신청 (#382). 주문상품 하나에 한 번만 (order_item_id 유니크). 거절, 완료된 신청도 지우지 않는다
@Entity
@Table(name = "return_request")
@NoArgsConstructor
@Getter
public class ReturnRequest extends BaseTimeEntity {

    public static final int REASON_DETAIL_MAX_LENGTH = 500;

    // 판매자 응답 기간: 반품 신청 시각부터 이 시간 안에 승인, 거절한다. 지나면(같거나 지남) 자동 승인 대상 (#383).
    // 응답 마감(returnDeadlineAt) 계산과 자동 승인 판정이 같은 값, 같은 경계를 쓴다
    public static final Duration RESPONSE_PERIOD = Duration.ofHours(48);

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

    // 판매자 승인 또는 자동 승인 (#383). 반품 배송비를 스냅샷으로 남기고 반품 배송을 연결한다
    public void approve(ReturnDecidedBy decidedBy, LocalDateTime decidedAt, BigDecimal returnShippingFee,
                        Long shipmentId) {
        requireRequested();
        this.status = ReturnRequestStatus.APPROVED;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.returnShippingFee = returnShippingFee;
        this.shipmentId = shipmentId;
    }

    // 판매자 거절 (#383). 반품 배송비, 반품 배송은 없다
    public void reject(ReturnDecidedBy decidedBy, LocalDateTime decidedAt) {
        requireRequested();
        this.status = ReturnRequestStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    public boolean isRequested() {
        return status == ReturnRequestStatus.REQUESTED;
    }

    // 응답 마감 = 신청 시각 + 응답 기간. 판매자 응답 전(REQUESTED)에만 값이 있다
    public LocalDateTime responseDeadline() {
        return isRequested() ? requestedAt.plus(RESPONSE_PERIOD) : null;
    }

    // 응답 기간이 끝났는지: 지금 >= 신청 시각 + 응답 기간
    public boolean isResponsePeriodOver(LocalDateTime now) {
        return !requestedAt.plus(RESPONSE_PERIOD).isAfter(now);
    }

    private void requireRequested() {
        if (this.status != ReturnRequestStatus.REQUESTED) {
            throw new IllegalStateException("응답 전 반품 신청만 승인, 거절할 수 있습니다. 현재 상태: " + this.status);
        }
    }
}
