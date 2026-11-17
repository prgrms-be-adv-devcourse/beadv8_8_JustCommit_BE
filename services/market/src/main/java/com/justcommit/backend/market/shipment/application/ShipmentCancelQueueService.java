package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderShippingInfo;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentCancelResult;
import com.justcommit.backend.market.shipment.domain.ShipmentStatus;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierCancelResult;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierClient;
import com.justcommit.backend.market.shipment.infrastructure.courier.CourierShipmentView;
import com.justcommit.backend.market.shipment.infrastructure.kafka.ShipmentStatusChangedEventPublisher;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentCancelResultRepository;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

// 택배 취소 큐 (ADR 0025): 취소 대기(CANCEL_PENDING) 배송을 택배사에 취소하고 결과를 남긴다.
// 택배 예약 큐와 같은 방식: 한 건씩 짧은 트랜잭션으로 집고(잠금 + 30초 임대) 바로 커밋 → 트랜잭션 밖에서 택배사 호출 →
// 짧은 트랜잭션으로 배송 상태 + 결과 행(SHIPMENT_CANCEL_RESULT)을 함께 저장. 실패하면 10초 → 30초 → 1분 뒤 다시 (끝까지).
//
// 택배사 호출은 지금 있는 API로 한다 (404-express에 주문번호 취소 · 누른 시각 판정이 생기면 callCourier만 바꾼다):
//   송장번호가 없으면 주문번호로 예약을 찾고 → 예약이 없으면 택배사에 건이 없으므로 취소 완료
//   → 있으면 송장번호로 취소: 200 취소됨, 409 취소 불가(기사 2km 안 · 수거), 404 예약 없음
@Slf4j
@Service
@RequiredArgsConstructor
public class ShipmentCancelQueueService {
    // 한 번 실행에 처리하는 최대 건수. 한 건씩 집으므로 택배사가 느려도 임대(30초)를 넘기지 않는다.
    private static final int CANCEL_BATCH_SIZE = 20;

    private final ShipmentRepository shipmentRepository;
    private final ShipmentCancelResultRepository cancelResultRepository;
    private final CourierClient courierClient;
    private final OrderQuery orderQuery;
    private final ShipmentStatusChangedEventPublisher statusChangedEventPublisher;
    private final PlatformTransactionManager transactionManager;

    public void cancelPendingShipments() {
        for (int i = 0; i < CANCEL_BATCH_SIZE; i++) {
            Shipment shipment = claimNext(LocalDateTime.now());
            if (shipment == null) {
                return;
            }
            cancelClaimed(shipment);
        }
    }

    private Shipment claimNext(LocalDateTime now) {
        return new TransactionTemplate(transactionManager).execute(status -> shipmentRepository.findNextCancellable(now)
                .map(shipment -> {
                    shipment.lease(now);
                    return shipmentRepository.saveAndFlush(shipment);
                })
                .orElse(null));
    }

    private void cancelClaimed(Shipment shipment) {
        CourierCancelOutcome outcome;
        try {
            outcome = callCourier(shipment);
        } catch (RuntimeException e) {
            // 택배사 응답 없음 · 5xx 등: 결과를 모르므로 CANCEL_PENDING 그대로 두고 다시 보낸다
            log.warn("택배 취소 실패, 나중에 다시 시도: shipmentId={}", shipment.getId(), e);
            recordFailure(shipment.getId(), e);
            return;
        }
        try {
            saveResult(shipment, outcome);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 임대 중에는 다른 파드 · 요청이 이 행을 바꾸지 않는다. 바뀌었다면 다음 주기에 상태를 보고 다시 판단한다
            log.warn("택배 취소 결과 저장 중 배송이 바뀜, 다시 시도: shipmentId={}", shipment.getId());
        } catch (RuntimeException e) {
            // 주문 정보 조회 · DB 오류 등. 택배사 취소는 멱등이라 다음 시도에서 같은 결과를 다시 받아 저장한다
            log.warn("택배 취소 결과 저장 실패, 나중에 다시 시도: shipmentId={}", shipment.getId(), e);
            recordFailure(shipment.getId(), e);
        }
    }

    private CourierCancelOutcome callCourier(Shipment shipment) {
        String trackingNo = shipment.getTrackingNo();
        if (trackingNo == null) {
            Optional<CourierShipmentView> found = courierClient.findByCourierOrderNo(shipment.getCourierOrderNo());
            if (found.isEmpty()) {
                return CourierCancelOutcome.CANCELLED; // 택배사에 예약이 없음 → 서버 취소만으로 끝
            }
            trackingNo = found.get().trackingNo();
        }
        CourierCancelResult result = courierClient.cancel(trackingNo);
        return switch (result) {
            case CANCELLED, NOT_FOUND -> CourierCancelOutcome.CANCELLED;
            case NOT_CANCELLABLE -> CourierCancelOutcome.LOCKED;
        };
    }

    private void saveResult(Shipment shipment, CourierCancelOutcome outcome) {
        LocalDateTime now = LocalDateTime.now();
        OrderShippingInfo orderInfo = orderQuery.getShippingInfo(shipment.getSellerOrderId());
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            if (outcome == CourierCancelOutcome.CANCELLED) {
                shipment.completeCancel(now);
                cancelResultRepository.save(ShipmentCancelResult.cancelled(shipment, false, now));
            } else {
                shipment.lockCancel();
                cancelResultRepository.save(ShipmentCancelResult.locked(shipment, now));
            }
            shipmentRepository.saveAndFlush(shipment);
            statusChangedEventPublisher.publishAfterCommit(shipment, orderInfo, now);
        });
        log.info("택배 취소 결과: shipmentId={}, result={}", shipment.getId(), outcome);
    }

    // 최신 행을 다시 읽어 실패를 기록한다. 그사이 상태가 바뀌었으면 그대로 둔다.
    private void recordFailure(Long shipmentId, RuntimeException e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    shipmentRepository.findById(shipmentId)
                            .filter(shipment -> shipment.getStatus() == ShipmentStatus.CANCEL_PENDING)
                            .ifPresent(shipment -> shipment.recordAttemptFailure(LocalDateTime.now(), reason)));
        } catch (ObjectOptimisticLockingFailureException ignored) {
            log.info("실패 기록 중 배송이 바뀜: shipmentId={}", shipmentId);
        }
    }

    private enum CourierCancelOutcome {
        CANCELLED, // 취소됨 (택배사에 예약이 없던 경우 포함)
        LOCKED     // 취소 불가 (기사 2km 안 · 이미 수거)
    }
}
