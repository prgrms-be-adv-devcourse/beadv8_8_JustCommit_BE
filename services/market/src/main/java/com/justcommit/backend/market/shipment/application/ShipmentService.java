package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.*;
import com.justcommit.backend.market.client.member.MemberAddress;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.client.member.SellerAddressUnavailableException;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.*;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

// 배송 로직(예약·재예약·취소). 다른 모듈에 여는 조회는 ShipmentQueryService가 맡는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentService {
    // 택배사가 보낸 +09:00 시각을 우리 기준(서울)으로 바꿀 때 쓴다. 현재 시각(now)은 서버 기본 시간대(운영: Asia/Seoul)를 따른다.
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 예약 큐가 한 번 실행할 때 처리하는 최대 건수. 한 건씩 집으므로 택배사가 느려도 임대(30초)를 넘기지 않는다.
    private static final int BOOKING_BATCH_SIZE = 20;

    private final ShipmentRepository shipmentRepository;
    private final CourierClient courierClient;
    private final MemberQuery memberQuery;
    private final PlatformTransactionManager transactionManager;
    private final ShipmentStatusChangedEventPublisher statusChangedEventPublisher;
    private final OrderQuery orderQuery;

    // 판매자 수락 트랜잭션 안에서 호출된다 (#284, ADR 0013). 수락, 일부 거절 뒤 수락된 주문상품만 담는다.
    // 배송 행(READY + courierOrderNo + 상품 요약)만 만들어 수락과 함께 커밋하고 끝낸다. 택배사는 부르지 않는다.
    // 택배사 예약은 예약 큐(bookReadyShipments)가 맡는다. 수락 응답이 택배사 지연 · 장애와 무관해진다 (#351).
    // 택배 주문번호의 날짜는 배송 생성일(= 수락일)이다
    public void createShipment(Long sellerOrderId, List<String> productNames) {
        Shipment shipment = shipmentRepository.save(new Shipment(sellerOrderId, itemSummary(productNames)));
        shipment.assignCourierOrderNo(LocalDate.now());
        shipmentRepository.save(shipment);
    }

    // 예약 큐: 시도할 때가 된 READY 배송을 한 건씩 확보해 예약한다. 한 번 실행에 최대 BOOKING_BATCH_SIZE건.
    // 한 건씩 짧은 트랜잭션으로 집고(잠금 + 30초 임대) 바로 커밋한 뒤, 트랜잭션 밖에서 택배사를 부른다.
    // 택배사를 기다리는 동안 DB 커넥션을 잡지 않는다 (#351 커넥션 교착의 원인).
    public void bookReadyShipments() {
        for (int i = 0; i < BOOKING_BATCH_SIZE; i++) {
            Shipment shipment = claimNextForBooking(LocalDateTime.now());
            if (shipment == null) {
                return;
            }
            bookClaimed(shipment);
        }
    }

    private Shipment claimNextForBooking(LocalDateTime now) {
        return new TransactionTemplate(transactionManager).execute(status -> shipmentRepository.findNextBookable(now)
                .map(shipment -> {
                    shipment.lease(now);
                    return shipmentRepository.saveAndFlush(shipment);
                })
                .orElse(null));
    }

    // 큐가 집은 배송을 택배사에 예약한다. 실패하면 READY로 두고 다음 시도 시각을 미룬다 (포기하지 않음).
    private void bookClaimed(Shipment shipment) {
        OrderShippingInfo orderInfo = null;
        CourierBookingResponse response = null;
        try {
            orderInfo = orderQuery.getShippingInfo(shipment.getSellerOrderId());
            response = courierClient.book(shipment.getCourierOrderNo(), bookingRequest(shipment, orderInfo));
            shipment.book(response.trackingNo(), response.bookedAt().atZoneSameInstant(SEOUL).toLocalDateTime());
            OrderShippingInfo info = orderInfo;
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                shipmentRepository.save(shipment);
                statusChangedEventPublisher.publishAfterCommit(shipment, info, shipment.getBookedAt());
            });
        } catch (ObjectOptimisticLockingFailureException e) {
            // 택배사 예약은 됐는데, 그사이 다른 쪽이 배송을 먼저 바꿨다
            recoverBookingConflict(shipment.getId(), response, orderInfo);
        } catch (RuntimeException e) {
            logBookingFailure(shipment, e);
            recordAttemptFailure(shipment.getId(), ShipmentStatus.READY, describe(e));
        }
    }

    // 예약 결과 저장이 다른 저장에 밀린 경우를 처리한다.
    // - 판매자가 집하점 2km 안이면 택배사가 예약 직후 CANCEL_LOCKED 웹훅을 보내 예약 저장보다 먼저 반영될 수 있다
    //   → 최신 행에 송장번호 · 예약 시각을 채우고, 판매자 알림용 BOOKED 이벤트를 보낸다
    // - 그사이 주문 취소가 먼저 저장됐다면(CANCEL_PENDING) 택배사 예약은 택배 취소 큐가 주문번호로 취소한다
    private void recoverBookingConflict(Long shipmentId, CourierBookingResponse response, OrderShippingInfo orderInfo) {
        LocalDateTime bookedAt = response.bookedAt().atZoneSameInstant(SEOUL).toLocalDateTime();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    shipmentRepository.findById(shipmentId).ifPresent(latest -> {
                        if (latest.fillMissedBooking(response.trackingNo(), bookedAt)) {
                            statusChangedEventPublisher.publishAfterCommit(latest, ShipmentStatus.BOOKED, orderInfo, bookedAt);
                            log.info("예약 저장보다 웹훅이 먼저 와 예약 정보만 채움: shipmentId={}, status={}", shipmentId, latest.getStatus());
                        } else {
                            log.info("예약 중 배송이 바뀜(취소 등): shipmentId={}, status={}", shipmentId, latest.getStatus());
                        }
                    }));
        } catch (ObjectOptimisticLockingFailureException again) {
            // 또 밀리면 이번 예약 알림은 포기한다 (배송 상태는 웹훅이 맞게 반영하고 있음)
            log.warn("예약 정보 채우기도 다른 저장에 밀림: shipmentId={}", shipmentId);
        }
    }

    private void logBookingFailure(Shipment shipment, RuntimeException e) {
        if (e instanceof SellerAddressUnavailableException) {
            // 이벤트 지연 · 탈퇴 등 원인은 단정하지 않는다. READY로 남아 같은 courierOrderNo로 다시 시도한다
            log.warn("판매자 배송지를 사용할 수 없어 예약을 보류합니다: shipmentId={}", shipment.getId());
        } else if (e instanceof CourierBookingException booking && booking.isRetryable()) {
            log.warn("택배 예약 실패, 나중에 다시 시도: shipmentId={}", shipment.getId(), e);
        } else {
            // 택배사가 거절(4xx)했거나 판매자 주소 조회 실패 등. 끝까지 재시도하되 사람이 확인하도록 남긴다
            log.error("택배 예약 실패, 확인 필요: shipmentId={}", shipment.getId(), e);
        }
    }

    // 최신 행을 다시 읽어 실패를 기록한다. 그사이 취소 등으로 큐가 집을 때의 상태가 아니면 그대로 둔다.
    private void recordAttemptFailure(Long shipmentId, ShipmentStatus expected, String reason) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    shipmentRepository.findById(shipmentId)
                            .filter(shipment -> shipment.getStatus() == expected)
                            .ifPresent(shipment -> shipment.recordAttemptFailure(LocalDateTime.now(), reason)));
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("실패 기록 중 배송이 바뀜: shipmentId={}", shipmentId);
        }
    }

    // last_error에 남길 원인: 예외 종류 + 메시지 (Outbox와 같은 모양)
    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    // 주문 모듈이 판매자 주문의 모든 상품이 취소됐을 때 주문 트랜잭션 안에서 호출한다.
    // 배송을 CANCEL_PENDING + 누른 시각으로 기록만 하고 주문과 함께 커밋한다. 택배사는 부르지 않는다 —
    // 택배 취소 큐가 보내고, 택배사가 누른 시각의 기사 거리로 판정한다 (ADR 0026).
    // 행을 잠그고 바꿔서 같은 취소가 동시에 와도(따닥 · 택배사 웹훅과 겹침) 한 번만 기록된다.
    @Transactional
    public void cancelShipment(Long sellerOrderId) {
        Shipment shipment = shipmentRepository.findForUpdateBySellerOrderIdAndDirection(sellerOrderId, ShipmentDirection.OUTBOUND)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SHIPMENT_NOT_FOUND));
        try {
            if (!shipment.requestCancel(LocalDateTime.now())) {
                return; // 이미 취소 중이거나 취소됨 (처음 누른 시각 유지)
            }
        } catch (IllegalStateException e) {
            // 기사 2km 안(CANCEL_LOCKED) 또는 수거 이후
            throw new BusinessException(OrderErrorCode.SHIPMENT_NOT_CANCELABLE);
        }
        shipmentRepository.save(shipment);
    }

    private CourierBookingRequest bookingRequest(Shipment shipment, OrderShippingInfo orderInfo) {
        MemberAddress seller = memberQuery.getDefaultAddress(orderInfo.sellerId());
        return new CourierBookingRequest(
                new CourierBookingRequest.Party(seller.recipientName(),
                        seller.recipientPhone(),
                        fullAddress(seller.address1(), seller.address2())),
                new CourierBookingRequest.Party(orderInfo.recipientName(),
                        orderInfo.recipientPhone(),
                        fullAddress(orderInfo.address1(), orderInfo.address2())),
                shipment.getItemSummary()
        );
    }

    private String itemSummary(List<String> productNames) {
        String first = productNames.getFirst();
        if (productNames.size() == 1) {
            return first;
        }
        return String.format("%s 외 %d건", first, productNames.size() - 1);
    }

    private String fullAddress(String address1, String address2) {
        if (address2 == null || address2.isBlank()) {
            return address1;
        }
        return address1 + " " + address2;
    }
}


