package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.ShipmentTracking;
import com.justcommit.backend.market.ShipmentTrackingQuery;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
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
    private final OrdersItemRepository ordersItemRepository;
    private final ReturnRequestRepository returnRequestRepository;
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

    // 반품 택배(#617), 판매자: 본인 판매자 주문이고 주문상품이 그 판매자 주문에 속할 때만
    public ShipmentTracking sellerReturnShipment(Long sellerId, Long sellerOrderId, Long orderItemId) {
        sellerOrderRepository.findByIdAndSellerId(sellerOrderId, sellerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        Long ownerSellerOrderId = ordersItemRepository.findSellerOrderIdByItemId(orderItemId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        if (!Objects.equals(ownerSellerOrderId, sellerOrderId)) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        return trackReturn(orderItemId);
    }

    // 반품 택배(#617), 구매자: 본인 주문이고 주문상품이 그 주문에 속할 때만
    public ShipmentTracking buyerReturnShipment(Long buyerId, Long orderId, Long orderItemId) {
        ordersRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        Long ownerOrderId = ordersItemRepository.findOrderIdByItemId(orderItemId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        if (!Objects.equals(ownerOrderId, orderId)) {
            throw new BusinessException(OrderErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        return trackReturn(orderItemId);
    }

    // 반품 신청이 없거나, 승인 전이라 반품 배송이 아직 연결되지 않았으면 404
    private ShipmentTracking trackReturn(Long orderItemId) {
        return returnRequestRepository.findByOrderItemId(orderItemId)
                .map(ReturnRequest::getShipmentId)
                .flatMap(shipmentTrackingQuery::trackReturn)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SHIPMENT_TRACKING_NOT_FOUND));
    }

    private ShipmentTracking track(Long sellerOrderId) {
        return shipmentTrackingQuery.track(sellerOrderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SHIPMENT_TRACKING_NOT_FOUND));
    }
}
