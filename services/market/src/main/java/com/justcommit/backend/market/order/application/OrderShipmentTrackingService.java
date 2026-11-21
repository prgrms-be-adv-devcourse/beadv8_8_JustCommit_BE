package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.ShipmentTracking;
import com.justcommit.backend.market.ShipmentTrackingQuery;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

// 배송 조회(#588): 본인 주문인지 확인한 뒤 배송 조회에 넘긴다.
// 배송 조회가 택배사를 부르므로 일부러 @Transactional을 두지 않는다 (권한 확인은 리포지토리 호출마다 짧게 끝남)
@Service
@RequiredArgsConstructor
public class OrderShipmentTrackingService {

    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final ShipmentTrackingQuery shipmentTrackingQuery;

    // 판매자: 본인 판매자 주문만. 남의 것은 있는지도 알리지 않게 404
    public ShipmentTracking sellerShipment(Long sellerId, Long sellerOrderId) {
        sellerOrderRepository.findByIdAndSellerId(sellerOrderId, sellerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        return track(sellerOrderId);
    }

    // 구매자: 본인 주문이고, 판매자 주문이 그 주문에 속할 때만
    public ShipmentTracking buyerShipment(Long buyerId, Long orderId, Long sellerOrderId) {
        ordersRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        Long ownerOrderId = sellerOrderRepository.findOrderIdById(sellerOrderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        if (!Objects.equals(ownerOrderId, orderId)) {
            throw new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND);
        }
        return track(sellerOrderId);
    }

    private ShipmentTracking track(Long sellerOrderId) {
        return shipmentTrackingQuery.track(sellerOrderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SHIPMENT_TRACKING_NOT_FOUND));
    }
}
