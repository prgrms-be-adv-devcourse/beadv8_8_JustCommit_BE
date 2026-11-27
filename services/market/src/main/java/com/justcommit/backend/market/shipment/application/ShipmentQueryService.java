package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.ShipmentCancelResultView;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.shipment.domain.Shipment;
import com.justcommit.backend.market.shipment.domain.ShipmentCancelResult;
import com.justcommit.backend.market.shipment.domain.ShipmentDirection;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentCancelResultRepository;
import com.justcommit.backend.market.shipment.infrastructure.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

// 다른 모듈에 여는 배송 조회. 배송 로직(ShipmentService)과 나눠 두어,
// 주문 조회가 배송 조회를 쓰고 배송 로직이 주문 조회를 써도 서로 물리지 않는다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShipmentQueryService implements ShipmentQuery {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentCancelResultRepository cancelResultRepository;

    // 판매자 주문의 가는 길 배송 (반품 배송은 보지 않는다, #382)
    @Override
    public ShipmentResult findBySellerOrderId(Long sellerOrderId) {
        return shipmentRepository.findBySellerOrderIdAndDirection(sellerOrderId, ShipmentDirection.OUTBOUND)
                .map(this::toResult)
                .orElse(null);
    }

    @Override
    public Map<Long, ShipmentResult> findBySellerOrderIds(List<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        return shipmentRepository.findBySellerOrderIdInAndDirection(sellerOrderIds, ShipmentDirection.OUTBOUND).stream()
                .collect(Collectors.toMap(Shipment::getSellerOrderId, this::toResult));
    }

    // 배송 id로 조회 (방향 무관, 반품 도착 처리용 #384)
    @Override
    public Map<Long, ShipmentResult> findByShipmentIds(List<Long> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return Map.of();
        }
        return shipmentRepository.findAllById(shipmentIds).stream()
                .collect(Collectors.toMap(Shipment::getId, this::toResult));
    }

    @Override
    public List<Long> findUnprocessedCancelResultIds(int limit) {
        return cancelResultRepository.findUnprocessedIds(limit);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ShipmentCancelResultView> lockUnprocessedCancelResult(Long resultId) {
        return cancelResultRepository.findUnprocessedForUpdateSkipLocked(resultId)
                .map(result -> new ShipmentCancelResultView(result.getId(), result.getSellerOrderId(),
                        result.getResult() == ShipmentCancelResult.Result.CANCELLED, result.isCourierReturn()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markCancelResultProcessed(Long resultId, LocalDateTime processedAt) {
        cancelResultRepository.findById(resultId).ifPresent(result -> result.markProcessed(processedAt));
    }

    private ShipmentResult toResult(Shipment shipment) {
        return new ShipmentResult(shipment.getId(), shipment.getStatus().publicName(), shipment.getDeliveredAt(),
                shipment.getTrackingNo(), shipment.getBookedAt(), shipment.getPickedUpAt(), shipment.getInTransitAt(),
                shipment.getItemSummary());
    }
}
