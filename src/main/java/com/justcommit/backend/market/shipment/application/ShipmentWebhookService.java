package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.ShipmentPickedUpEvent;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentPickedUpEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.market.shipment.presentation.CourierWebhookRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentWebhookService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ShipmentRepository shipmentRepository;
    private final ShipmentPickedUpEventPublisher pickedUpEventPublisher;
    private final ShipmentStatusChangedEventPublisher statusChangedEventPublisher;

    // 택배사 웹훅을 배송 상태에 반영한다. 다시 보내도 결과가 같은 경우는 예외 없이 끝내서 택배사가 재시도하지 않게 한다.
    @Transactional
    public void receive(CourierWebhookRequest request) {
        Shipment shipment = shipmentRepository.findByCourierOrderNo(request.courierOrderNo()).orElse(null);
        if (shipment == null) {
            log.error("모르는 택배 주문번호 웹훅: courierOrderNo={}, eventId={}", request.courierOrderNo(), request.eventId());
            return;
        }

        ShipmentStatus newStatus = toShipmentStatus(request.status());
        if (newStatus == null) {
            log.error("모르는 택배 상태 웹훅: status={}, courierOrderNo={}, eventId={}",
                    request.status(), request.courierOrderNo(), request.eventId());
            return;
        }
        LocalDateTime occurredAt = request.occurredAt().atZoneSameInstant(SEOUL).toLocalDateTime();

        boolean wasPickedUp = shipment.isPickedUp();
        boolean applied = shipment.applyCourierStatus(newStatus, request.trackingNo(), occurredAt);
        if (!applied) {
            log.info("반영하지 않은 웹훅 (중복 또는 순서 뒤바뀜): eventId={}, courierOrderNo={}, 현재={}, 받은={}",
                    request.eventId(), request.courierOrderNo(), shipment.getStatus(), newStatus);
            return;
        }
        statusChangedEventPublisher.publishAfterCommit(shipment, occurredAt);

        // 처음으로 수거 이후가 됐을 때 한 번만 보낸다. PICKED_UP 웹훅을 놓치고 IN_TRANSIT이 먼저 와도 여기서 보낸다.
        if (!wasPickedUp && shipment.isPickedUp()) {
            LocalDateTime pickedUpAt = shipment.getPickedUpAt() != null ? shipment.getPickedUpAt() : occurredAt;
            pickedUpEventPublisher.publishAfterCommit(new ShipmentPickedUpEvent(
                    shipment.getSellerOrderId(), shipment.getSellerId(), shipment.getShippingFee(), pickedUpAt));
        }
    }

    // 웹훅으로 오는 택배사 상태만 받는다. 그 밖의 값은 null
    private ShipmentStatus toShipmentStatus(String courierStatus) {
        return switch (courierStatus) {
            case "PICKED_UP" -> ShipmentStatus.PICKED_UP;
            case "IN_TRANSIT" -> ShipmentStatus.IN_TRANSIT;
            case "DELIVERED" -> ShipmentStatus.DELIVERED;
            default -> null;
        };
    }
}
