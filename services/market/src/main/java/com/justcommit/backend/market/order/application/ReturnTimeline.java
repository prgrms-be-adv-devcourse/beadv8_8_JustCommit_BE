package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.domain.ReturnRequestStatus;

import java.time.LocalDateTime;

/**
 * 주문 상세의 반품 정보 (#606). 반품 신청과 반품 배송(방향 = 반품)에서 단계 시각을 모은다. 아직 안 된 단계는 null.
 * <ul>
 *   <li>decidedAt: 판매자 승인, 자동 승인, 거절 시각</li>
 *   <li>arrivedAt: 반품 배송 완료(판매자 도착) 시각. 반품 도착 처리가 이 배송의 DELIVERED를 보고 반품 환불 중으로 바꾼다</li>
 *   <li>returnedAt: 반품 환불 확정 시각. 전용 칸 없이 완료(COMPLETED) 반품 신청의 updated_at. 완료는 마지막 상태라 더 바뀌지 않는다</li>
 * </ul>
 */
public record ReturnTimeline(ReturnReason reason, String reasonDetail, LocalDateTime requestedAt,
                             LocalDateTime deadlineAt, LocalDateTime decidedAt, String shipmentStatus,
                             String trackingNo, LocalDateTime pickedUpAt, LocalDateTime inTransitAt,
                             LocalDateTime arrivedAt, LocalDateTime returnedAt) {

    // returnShipment: 반품 신청에 연결된 반품 배송. 승인 전, 거절이면 null
    public static ReturnTimeline of(ReturnRequest returnRequest, ShipmentResult returnShipment) {
        boolean completed = returnRequest.getStatus() == ReturnRequestStatus.COMPLETED;
        return new ReturnTimeline(returnRequest.getReason(), returnRequest.getReasonDetail(),
                returnRequest.getRequestedAt(), returnRequest.responseDeadline(), returnRequest.getDecidedAt(),
                returnShipment == null ? null : returnShipment.status(),
                returnShipment == null ? null : returnShipment.trackingNo(),
                returnShipment == null ? null : returnShipment.pickedUpAt(),
                returnShipment == null ? null : returnShipment.inTransitAt(),
                returnShipment == null ? null : returnShipment.deliveredAt(),
                completed ? returnRequest.getUpdatedAt() : null);
    }
}
