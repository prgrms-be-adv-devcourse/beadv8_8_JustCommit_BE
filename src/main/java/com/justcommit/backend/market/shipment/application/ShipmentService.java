package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingException;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService implements ShipmentQuery {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ShipmentRepository shipmentRepository;
    private final CourierClient courierClient;
    private final MemberQuery memberQuery;
    private final PlatformTransactionManager transactionManager;

    @Override
    @Transactional(readOnly = true)
    public ShipmentResult findBySellerOrderId(Long sellerOrderId) {
        return shipmentRepository.findBySellerOrderId(sellerOrderId)
                .map(shipment -> new ShipmentResult(shipment.getId(), shipment.getStatus().name(),
                        shipment.getDeliveredAt()))
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ShipmentResult> findBySellerOrderIds(List<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        return shipmentRepository.findBySellerOrderIdIn(sellerOrderIds).stream()
                .collect(Collectors.toMap(Shipment::getSellerOrderId,
                        shipment -> new ShipmentResult(shipment.getId(), shipment.getStatus().name(),
                                shipment.getDeliveredAt())));
    }


    // 1단계: 결제 트랜잭션 안에서 호출된다.
    // 배송 행(READY + courierOrderNo + 예약 정보)만 만들어 결제와 함께 커밋하고, 택배사 예약은 커밋 후로 미룬다.
    public void createShipments(OrderResult order) {
        String receiverAddress = fullAddress(order.address1(), order.address2());
        List<Shipment> shipments = new ArrayList<>();
        for (SellerOrderResult sellerOrder : order.sellerOrders()) {
            Shipment shipment = shipmentRepository.save(new Shipment(sellerOrder.id(), sellerOrder.sellerId(),
                    order.recipientName(), order.recipientPhone(), receiverAddress,
                    itemSummary(sellerOrder.items()), sellerOrder.shippingFee()));
            shipment.assignCourierOrderNo(LocalDate.now(SEOUL));
            shipments.add(shipmentRepository.save(shipment));
        }
        runAfterCommit(() -> bookAll(shipments));
    }

    // 대사: 예약되지 않고 남은 READY 배송을 같은 courierOrderNo(멱등키)로 다시 예약한다.
    // 트랜잭션 밖에서 호출해야 택배사를 기다리는 동안 트랜잭션을 잡지 않는다.
    public void rebookReadyShipments(LocalDateTime createdBefore) {
        List<Shipment> shipments = shipmentRepository
                .findTop100ByStatusAndCreatedAtBeforeOrderByIdAsc(ShipmentStatus.READY, createdBefore);
        if (!shipments.isEmpty()) {
            log.info("READY 배송 재예약: {}건", shipments.size());
        }
        bookAll(shipments);
    }

    // 2단계: 결제 커밋 후, 트랜잭션 밖에서 택배사에 예약한다.
    // 실패한 배송은 READY로 남고, 대사 작업이 같은 courierOrderNo로 다시 예약한다.
    private void bookAll(List<Shipment> shipments) {
        for (Shipment shipment : shipments) {
            try {
                book(shipment);
            } catch (RuntimeException e) {
                // 판매자 주소 조회 실패 등. 한 판매자의 실패가 다른 판매자 예약을 막지 않게 한다.
                log.error("택배 예약 준비 실패: shipmentId={}", shipment.getId(), e);
            }
        }
    }

    // 트랜잭션 안이면 커밋 후에, 밖이면 바로 실행한다.
    // 커밋 후 콜백은 끝난 트랜잭션에 묶여 있어서 그대로 저장하면 커밋되지 않으므로 트랜잭션을 떼어 내고 실행한다.
    private void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                TransactionTemplate outsideTransaction = new TransactionTemplate(transactionManager);
                outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                outsideTransaction.executeWithoutResult(status -> task.run());
            }
        });
    }

    private void book(Shipment shipment) {
        MemberAddress seller = memberQuery.getDefaultAddress(shipment.getSellerId());
        CourierBookingRequest request = new CourierBookingRequest(
                new CourierBookingRequest.Party(seller.recipientName(),
                        seller.recipientPhone(),
                        fullAddress(seller.address1(), seller.address2())),
                new CourierBookingRequest.Party(shipment.getReceiverName(),
                        shipment.getReceiverPhone(),
                        shipment.getReceiverAddress()),
                shipment.getItemSummary()
        );

        try {
            CourierBookingResponse response = courierClient.book(shipment.getCourierOrderNo(), request);
            shipment.book(response.trackingNo(), response.bookedAt().atZoneSameInstant(SEOUL).toLocalDateTime());
            shipmentRepository.save(shipment);
        } catch (CourierBookingException e) {
            if (e.isRetryable()) {
                log.warn("택배 예약 실패, 나중에 다시 시도: shipmentId={}", shipment.getId(), e);
            } else {
                log.error("택배 예약 실패, 확인 필요: shipmentId={}", shipment.getId(), e);
            }
        }
    }

    private String itemSummary(List<OrdersItemResult> items) {
        String first = items.getFirst().productName();
        if (items.size() == 1) {
            return String.format("%s", first);
        }
        return String.format("%s 외 %d건", first, items.size() - 1);
    }

    private String fullAddress(String address1, String address2) {
        if (address2 == null || address2.isBlank()) {
            return address1;
        }
        return address1 + " " + address2;
    }
}


