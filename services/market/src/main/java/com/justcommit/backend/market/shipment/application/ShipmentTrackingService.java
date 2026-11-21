package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.ShipmentTracking;
import com.justcommit.backend.market.ShipmentTrackingQuery;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierShipmentView;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

// 화면용 배송 조회 (#588). 일부러 @Transactional을 두지 않는다:
// 배송 행은 리포지토리 호출 하나(짧은 읽기 트랜잭션)로 읽고, 택배사는 DB 커넥션을 놓은 뒤 부른다.
@Service
@RequiredArgsConstructor
public class ShipmentTrackingService implements ShipmentTrackingQuery {

    // 택배사에 예약돼 있고 아직 끝나지 않은 단계만 택배사에 묻는다. READY는 예약 전, DELIVERED · CANCELLED는 끝나서 거리가 없다
    private static final Set<ShipmentStatus> COURIER_TRACKABLE = EnumSet.of(
            ShipmentStatus.BOOKED, ShipmentStatus.CANCEL_LOCKED, ShipmentStatus.PICKED_UP,
            ShipmentStatus.IN_TRANSIT, ShipmentStatus.CANCEL_PENDING);

    private final ShipmentRepository shipmentRepository;
    private final CourierClient courierClient;

    @Override
    public Optional<ShipmentTracking> track(Long sellerOrderId) {
        return shipmentRepository.findBySellerOrderIdAndDirection(sellerOrderId, ShipmentDirection.OUTBOUND)
                .map(this::withCourierView);
    }

    private ShipmentTracking withCourierView(Shipment shipment) {
        Optional<CourierShipmentView> view = Optional.empty();
        LocalDateTime checkedAt = null;
        if (shipment.getCourierOrderNo() != null && COURIER_TRACKABLE.contains(shipment.getStatus())) {
            view = courierClient.track(shipment.getCourierOrderNo()); // 실패 · 시간 초과면 빈 값
            if (view.isPresent()) {
                checkedAt = LocalDateTime.now();
            }
        }
        return new ShipmentTracking(
                shipment.getStatus().name(), shipment.getTrackingNo(),
                shipment.getBookedAt(), shipment.getPickedUpAt(), shipment.getInTransitAt(),
                shipment.getDeliveredAt(), shipment.getCancelledAt(),
                view.map(CourierShipmentView::status).orElse(null),
                view.map(CourierShipmentView::remainingDistanceMeters).orElse(null),
                view.map(CourierShipmentView::locationName).orElse(null),
                checkedAt);
    }
}
