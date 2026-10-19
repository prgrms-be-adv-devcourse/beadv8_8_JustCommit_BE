package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.response.PagingResponse;
import com.justcommit.backend.market.OrderQuery;
import com.justcommit.backend.market.OrderResult;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.OrdersItemResult;
import com.justcommit.backend.market.SellerOrderResult;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.presentation.OrderDetailResponse;
import com.justcommit.backend.market.order.presentation.OrderListRequest;
import com.justcommit.backend.market.order.presentation.OrderResponse;
import com.justcommit.backend.market.order.presentation.SellerOrderDetailResponse;
import com.justcommit.backend.market.order.presentation.SellerOrderResponse;
import com.justcommit.backend.settlement.SettlementQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderReadService implements OrderQuery {

    private final OrdersRepository ordersRepository;
    private final SellerOrderRepository sellerOrderRepository;
    private final OrdersItemRepository ordersItemRepository;
    private final ShipmentQuery shipmentQuery;
    private final SettlementQuery settlementQuery;

    public PagingResponse<OrderResponse> buyerOrders(Long buyerId, OrderListRequest request) {
        Page<Orders> page = ordersRepository.findByBuyerId(buyerId, request.toPageable());
        List<Long> orderIds = page.getContent().stream().map(Orders::getId).toList();
        Map<Long, String> firstNames = new HashMap<>();
        if (!orderIds.isEmpty()) {
            for (OrdersItem item : ordersItemRepository.findByOrderIds(orderIds)) {
                firstNames.putIfAbsent(item.getSellerOrder().getOrder().getId(), item.getProductName());
            }
        }
        return PagingResponse.from(page.map(order -> new OrderResponse(
                order.getId(), order.getOrderNo(), order.getTotalAmount(), order.getItemCount(),
                order.getStatus(), firstNames.get(order.getId()), order.getCreatedAt())));
    }

    public OrderDetailResponse buyerOrder(Long buyerId, Long orderId) {
        Orders order = ordersRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        List<SellerOrder> sellerOrderEntities = sellerOrderRepository.findByOrder_Id(order.getId());
        List<Long> sellerOrderIds = sellerOrderEntities.stream().map(SellerOrder::getId).toList();
        Map<Long, List<OrdersItemResult>> items = itemsBySellerOrder(sellerOrderIds);
        Map<Long, ShipmentResult> shipments = shipmentsBySellerOrder(sellerOrderIds);
        List<OrderDetailResponse.SellerOrderDetail> sellerOrders = sellerOrderEntities.stream()
                .map(sellerOrder -> toBuyerDetail(toResult(sellerOrder, items, shipments), order.getStatus(),
                        shipments.get(sellerOrder.getId())))
                .toList();
        return new OrderDetailResponse(order.getId(), order.getOrderNo(), order.getTotalAmount(),
                order.getItemCount(), order.getStatus(), order.getCreatedAt(), order.getRecipientName(),
                order.getRecipientPhone(), order.getZipcode(), order.getShipAddress1(), order.getShipAddress2(),
                sellerOrders);
    }

    public PagingResponse<SellerOrderResponse> sellerOrders(Long sellerId, OrderListRequest request) {
        Page<SellerOrder> page = sellerOrderRepository.findBySellerId(sellerId, request.toPageable());
        List<Long> sellerOrderIds = page.getContent().stream().map(SellerOrder::getId).toList();
        Map<Long, String> firstNames = new HashMap<>();
        Map<Long, Integer> itemCounts = new HashMap<>();
        Map<Long, ShipmentResult> shipments = shipmentsBySellerOrder(sellerOrderIds);
        if (!sellerOrderIds.isEmpty()) {
            for (OrdersItem item : ordersItemRepository.findBySellerOrderIds(sellerOrderIds)) {
                Long id = item.getSellerOrder().getId();
                firstNames.putIfAbsent(id, item.getProductName());
                itemCounts.merge(id, 1, Integer::sum);
            }
        }
        return PagingResponse.from(page.map(sellerOrder -> {
            Orders order = sellerOrder.getOrder();
            ShipmentResult shipment = shipments.get(sellerOrder.getId());
            return new SellerOrderResponse(sellerOrder.getId(), order.getId(), order.getOrderNo(),
                    sellerOrder.getTotalAmount().add(sellerOrder.getShippingFee()),
                    itemCounts.getOrDefault(sellerOrder.getId(), 0), order.getStatus(),
                    shipment == null ? null : shipment.status(), firstNames.get(sellerOrder.getId()),
                    sellerOrder.getCreatedAt());
        }));
    }

    public SellerOrderDetailResponse sellerOrder(Long sellerId, Long sellerOrderId) {
        SellerOrder sellerOrder = sellerOrderRepository.findByIdAndSellerId(sellerOrderId, sellerId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        Orders order = sellerOrder.getOrder();
        List<OrdersItemResult> items = itemsBySellerOrder(List.of(sellerOrderId)).getOrDefault(sellerOrderId, List.of());
        ShipmentResult shipment = shipmentQuery.findBySellerOrderId(sellerOrderId);
        Optional<LocalDateTime> settledAt = settlementQuery.findSettledAtBySellerOrderId(sellerOrderId);
        // 정산 테이블에는 별도 상태 컬럼이 없으므로 실제 정산 행의 존재 여부만 표시한다.
        return new SellerOrderDetailResponse(sellerOrderId, order.getId(), order.getOrderNo(),
                sellerOrder.getTotalAmount().add(sellerOrder.getShippingFee()), sellerOrder.getShippingFee(),
                order.getStatus(), shipment == null ? null : shipment.status(),
                shipment == null ? null : shipment.trackingNo(),
                shipment == null ? null : shipment.bookedAt(),
                shipment == null ? null : shipment.pickedUpAt(),
                shipment == null ? null : shipment.inTransitAt(),
                shipment == null ? null : shipment.deliveredAt(),
                settledAt.isPresent() ? "COMPLETED" : "NOT_SETTLED", settledAt.orElse(null), sellerOrder.getCreatedAt(),
                order.getRecipientName(), order.getRecipientPhone(),
                order.getZipcode(), order.getShipAddress1(), order.getShipAddress2(), items);
    }

    @Override
    public OrderResult getOrder(Long orderId) {
        Orders order = ordersRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        return new OrderResult(order.getId(), order.getBuyerId(), sellerResults(orderId), order.getTotalAmount(),
                order.getRecipientName(), order.getRecipientPhone(), order.getZipcode(),
                order.getShipAddress1(), order.getShipAddress2(), order.getItemCount(), order.getStatus());
    }

    @Override
    public SellerOrderResult getSellerOrder(Long sellerOrderId) {
        SellerOrder sellerOrder = sellerOrderRepository.findById(sellerOrderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        return toResult(sellerOrder, itemsBySellerOrder(List.of(sellerOrderId)),
                shipmentsBySellerOrder(List.of(sellerOrderId)));
    }

    @Override
    public List<SellerOrderResult> getSellerOrderBySellerId(Long sellerId) {
        List<SellerOrder> sellerOrders = sellerOrderRepository.findBySellerId(sellerId);
        Map<Long, List<OrdersItemResult>> items = itemsBySellerOrder(
                sellerOrders.stream().map(SellerOrder::getId).toList());
        Map<Long, ShipmentResult> shipments = shipmentsBySellerOrder(
                sellerOrders.stream().map(SellerOrder::getId).toList());
        return sellerOrders.stream().map(sellerOrder -> toResult(sellerOrder, items, shipments)).toList();
    }

    private List<SellerOrderResult> sellerResults(Long orderId) {
        List<SellerOrder> sellerOrders = sellerOrderRepository.findByOrder_Id(orderId);
        Map<Long, List<OrdersItemResult>> items = itemsBySellerOrder(
                sellerOrders.stream().map(SellerOrder::getId).toList());
        Map<Long, ShipmentResult> shipments = shipmentsBySellerOrder(
                sellerOrders.stream().map(SellerOrder::getId).toList());
        return sellerOrders.stream().map(sellerOrder -> toResult(sellerOrder, items, shipments)).toList();
    }

    private Map<Long, List<OrdersItemResult>> itemsBySellerOrder(List<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<OrdersItemResult>> result = new LinkedHashMap<>();
        for (OrdersItem item : ordersItemRepository.findBySellerOrderIds(sellerOrderIds)) {
            result.computeIfAbsent(item.getSellerOrder().getId(), ignored -> new ArrayList<>())
                    .add(new OrdersItemResult(item.getId(), item.getProductId(), item.getProductName(),
                            item.getPrice(), item.getStatus()));
        }
        return result;
    }

    private Map<Long, ShipmentResult> shipmentsBySellerOrder(List<Long> sellerOrderIds) {
        if (sellerOrderIds.isEmpty()) {
            return Map.of();
        }
        return shipmentQuery.findBySellerOrderIds(sellerOrderIds);
    }

    private SellerOrderResult toResult(SellerOrder sellerOrder, Map<Long, List<OrdersItemResult>> items,
                                       Map<Long, ShipmentResult> shipments) {
        ShipmentResult shipment = shipments.get(sellerOrder.getId());
        return new SellerOrderResult(sellerOrder.getId(), sellerOrder.getSellerId(),
                items.getOrDefault(sellerOrder.getId(), List.of()), sellerOrder.getTotalAmount(),
                sellerOrder.getConfirmedAt(), sellerOrder.getShippingFee(),
                shipment == null ? null : shipment.shipmentId(),
                shipment == null ? null : shipment.status(),
                shipment == null ? null : shipment.deliveredAt());
    }

    private OrderDetailResponse.SellerOrderDetail toBuyerDetail(SellerOrderResult seller, OrderStatus orderStatus,
                                                                ShipmentResult shipment) {
        boolean delivered = "DELIVERED".equals(seller.shipmentStatus());
        List<OrderDetailResponse.OrderItemDetail> items = seller.items().stream()
                .map(item -> new OrderDetailResponse.OrderItemDetail(item.id(), item.productId(),
                        item.productName(), item.price(), item.status(),
                        orderStatus == OrderStatus.PAID && delivered && item.status() == OrderItemStatus.NORMAL))
                .toList();
        return new OrderDetailResponse.SellerOrderDetail(seller.id(), seller.sellerId(), items,
                seller.totalAmount(), seller.confirmedAt(), seller.shippingFee(), seller.shipmentId(),
                seller.shipmentStatus(),
                shipment == null ? null : shipment.bookedAt(),
                shipment == null ? null : shipment.pickedUpAt(),
                shipment == null ? null : shipment.inTransitAt(),
                seller.deliveredAt());
    }
}
