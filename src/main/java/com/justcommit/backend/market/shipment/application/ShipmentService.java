package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingException;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingRequest;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierBookingResponse;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierCancelResult;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    // 택배사가 보낸 +09:00 시각을 우리 기준(서울)으로 바꿀 때 쓴다. 현재 시각(now)은 서버 기본 시간대(운영: Asia/Seoul)를 따른다.
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ShipmentRepository shipmentRepository;
    private final CourierClient courierClient;
    private final MemberQuery memberQuery;
    private final PlatformTransactionManager transactionManager;
    private final ShipmentStatusChangedEventPublisher statusChangedEventPublisher;

    @Override
    @Transactional(readOnly = true)
    public ShipmentResult findBySellerOrderId(Long sellerOrderId) {
        return shipmentRepository.findBySellerOrderId(sellerOrderId)
                .map(this::toResult)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, ShipmentResult> findBySellerOrderIds(List<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        return shipmentRepository.findBySellerOrderIdIn(sellerOrderIds).stream()
                .collect(Collectors.toMap(Shipment::getSellerOrderId, this::toResult));
    }

    private ShipmentResult toResult(Shipment shipment) {
        return new ShipmentResult(shipment.getId(), shipment.getStatus().name(), shipment.getDeliveredAt(),
                shipment.getTrackingNo(), shipment.getBookedAt(), shipment.getPickedUpAt(), shipment.getInTransitAt(),
                shipment.getItemSummary());
    }


    // 1단계: 결제 트랜잭션 안에서 호출된다.
    // 배송 행(READY + courierOrderNo + 예약 정보)만 만들어 결제와 함께 커밋하고, 택배사 예약은 커밋 후로 미룬다.
    public void createShipments(OrderResult order) {
        String receiverAddress = fullAddress(order.address1(), order.address2());
        List<Shipment> shipments = new ArrayList<>();
        for (SellerOrderResult sellerOrder : order.sellerOrders()) {
            Shipment shipment = shipmentRepository.save(new Shipment(order.id(), sellerOrder.id(), sellerOrder.sellerId(), order.buyerId(),
                    order.recipientName(), order.recipientPhone(), receiverAddress,
                    itemSummary(sellerOrder.items()), sellerOrder.shippingFee()));
            shipment.assignCourierOrderNo(LocalDate.now());
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
        try {
            CourierBookingResponse response = courierClient.book(shipment.getCourierOrderNo(), bookingRequest(shipment));
            shipment.book(response.trackingNo(), response.bookedAt().atZoneSameInstant(SEOUL).toLocalDateTime());
            shipmentRepository.save(shipment);
            statusChangedEventPublisher.publishAfterCommit(shipment, shipment.getBookedAt());
        } catch (CourierBookingException e) {
            if (e.isRetryable()) {
                log.warn("택배 예약 실패, 나중에 다시 시도: shipmentId={}", shipment.getId(), e);
            } else {
                log.error("택배 예약 실패, 확인 필요: shipmentId={}", shipment.getId(), e);
            }
        } catch (ObjectOptimisticLockingFailureException e) {
            // 예약하는 사이 주문 취소가 먼저 저장됨. 택배사에 남은 예약은 취소 쪽이 같은 멱등키로 찾아 취소한다.
            log.info("예약 중 배송이 취소됨: shipmentId={}", shipment.getId());
        }
    }

    // 주문 모듈이 판매자 주문의 모든 상품이 취소됐을 때 주문 트랜잭션 안에서 호출한다.
    // 배송만 CANCELLED로 바꿔 주문과 함께 커밋하고, 택배사 취소는 커밋 후로 미룬다.
    @Transactional
    public void cancelShipment(Long sellerOrderId) {
        Shipment shipment = shipmentRepository.findBySellerOrderId(sellerOrderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SHIPMENT_NOT_FOUND));
        if (shipment.isPickedUp()) {
            throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_CANCELABLE);
        }
        if (!shipment.cancel(LocalDateTime.now())) {
            return; // 이미 취소됨
        }
        shipmentRepository.save(shipment);
        runAfterCommit(() -> cancelAllAtCourier(List.of(shipment)));
    }

    // 대사: 택배사 취소가 끝나지 않은 CANCELLED 배송을 다시 취소한다. 취소는 멱등이라 그대로 다시 보내도 된다.
    public void retryCourierCancels(LocalDateTime cancelledBefore) {
        List<Shipment> shipments = shipmentRepository
                .findTop100ByStatusAndCourierCancelledAtIsNullAndCancelledAtBeforeOrderByIdAsc(
                        ShipmentStatus.CANCELLED, cancelledBefore);
        if (!shipments.isEmpty()) {
            log.info("택배사 취소 재시도: {}건", shipments.size());
        }
        cancelAllAtCourier(shipments);
    }

    private void cancelAllAtCourier(List<Shipment> shipments) {
        for (Shipment shipment : shipments) {
            try {
                cancelAtCourier(shipment);
            } catch (RuntimeException e) {
                // 택배사 응답 없음, 판매자 주소 조회 실패 등. 다음 대사 때 다시 시도한다.
                log.warn("택배사 취소 실패, 나중에 다시 시도: shipmentId={}", shipment.getId(), e);
            }
        }
    }

    private void cancelAtCourier(Shipment shipment) {
        String trackingNo = shipment.getTrackingNo();
        if (trackingNo == null) {
            // READY에서 취소됨. 예약 응답만 유실돼 택배사에 예약이 있을 수 있으므로 같은 멱등키로 송장번호를 받아 온다.
            try {
                trackingNo = courierClient.book(shipment.getCourierOrderNo(), bookingRequest(shipment)).trackingNo();
            } catch (CourierBookingException e) {
                if (e.isRetryable()) {
                    throw e;
                }
                // 택배사가 예약을 받지 않음(400·401 등) → 택배사에 건이 없으므로 서버 취소만으로 끝낸다.
                log.warn("택배사에 예약이 없어 서버만 취소: shipmentId={}", shipment.getId(), e);
                markCourierCancelled(shipment, null);
                return;
            }
        }

        CourierCancelResult result = courierClient.cancel(trackingNo);
        if (result == CourierCancelResult.NOT_CANCELLABLE) {
            // 취소와 수거가 거의 동시에 일어남. 자동으로 풀 수 없으니 사람이 확인한다.
            log.error("택배사에서 이미 수거되어 취소 불가, 확인 필요: shipmentId={}, trackingNo={}",
                    shipment.getId(), trackingNo);
        }
        markCourierCancelled(shipment, trackingNo);
    }

    private void markCourierCancelled(Shipment shipment, String trackingNo) {
        shipment.markCourierCancelled(trackingNo, LocalDateTime.now());
        shipmentRepository.save(shipment);
    }

    private CourierBookingRequest bookingRequest(Shipment shipment) {
        MemberAddress seller = memberQuery.getDefaultAddress(shipment.getSellerId());
        return new CourierBookingRequest(
                new CourierBookingRequest.Party(seller.recipientName(),
                        seller.recipientPhone(),
                        fullAddress(seller.address1(), seller.address2())),
                new CourierBookingRequest.Party(shipment.getReceiverName(),
                        shipment.getReceiverPhone(),
                        shipment.getReceiverAddress()),
                shipment.getItemSummary()
        );
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


