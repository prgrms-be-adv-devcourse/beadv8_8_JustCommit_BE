package com.justcommit.backend.market.order.application;

import com.justcommit.backend.market.ReturnReason;
import com.justcommit.backend.market.ReturnDecidedBy;
import com.justcommit.backend.market.order.domain.ReturnRequest;
import com.justcommit.backend.market.order.infrastructure.ReturnRequestRepository;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.OrderItemStatus;
import com.justcommit.backend.market.ShipmentQuery;
import com.justcommit.backend.market.ShipmentResult;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.presentation.OrderListRequest;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductResult;
import com.justcommit.backend.market.client.product.ProductStatus;
import com.justcommit.backend.market.client.settlement.SellerOrderSettlement;
import com.justcommit.backend.market.client.settlement.SettlementQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderReadServiceTest {

    @Mock OrdersRepository ordersRepository;
    @Mock SellerOrderRepository sellerOrderRepository;
    @Mock OrdersItemRepository ordersItemRepository;
    @Mock MemberQuery memberQuery;
    @Mock ShipmentQuery shipmentQuery;
    @Mock SettlementQuery settlementQuery;
    @Mock ProductQuery productQuery;
    @Mock ReturnRequestRepository returnRequestRepository;
    @InjectMocks OrderReadService orderReadService;

    @Test
    void buyerListUsesPageAndLoadsItemNamesInOneBatch() {
        Orders order = order(10L);
        OrdersItem item = item(order, 20L);
        when(ordersRepository.findByBuyerId(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));
        when(ordersItemRepository.findByOrderIds(List.of(9L))).thenReturn(List.of(item));
        when(productQuery.getProducts(List.of(101L))).thenReturn(List.of(product(101L, "https://img/monstera.jpg")));

        var response = orderReadService.buyerOrders(10L, new OrderListRequest(null, null));

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.orderId()).isEqualTo(9L);
            assertThat(summary.representativeItemName()).isEqualTo("몬스테라");
            assertThat(summary.thumbnailUrl()).isEqualTo("https://img/monstera.jpg"); // 대표 상품과 같은 상품의 사진
        });
        verify(productQuery).getProducts(List.of(101L)); // 목록 전체에 한 번
        assertThat(response.totalElements()).isEqualTo(1);
        verify(ordersItemRepository).findByOrderIds(List.of(9L));
    }

    @Test
    void buyerDetailLoadsOnlyOwnedOrder() {
        Orders order = order(10L);
        order.paid();
        OrdersItem item = item(order, 20L);
        SellerOrder sellerOrder = item.getSellerOrder();
        when(ordersRepository.findByIdAndBuyerId(9L, 10L)).thenReturn(Optional.of(order));
        when(sellerOrderRepository.findByOrder_Id(9L)).thenReturn(List.of(sellerOrder));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(List.of(item));

        var response = orderReadService.buyerOrder(10L, 9L);

        assertThat(response.sellerOrders()).singleElement().satisfies(seller -> {
            assertThat(seller.sellerId()).isEqualTo(20L);
            assertThat(seller.shipmentStatus()).isNull();
            assertThat(seller.items()).singleElement().satisfies(detail -> {
                assertThat(detail.productName()).isEqualTo("몬스테라");
                assertThat(detail.purchaseConfirmable()).isFalse();
            });
        });
        verify(ordersRepository).findByIdAndBuyerId(9L, 10L);
    }

    @Test
    void buyerDetailBatchesShipmentStatusAndDeterminesEligibilityPerItem() {
        Orders order = order(10L);
        order.paid();
        OrdersItem deliveredItem = item(order, 20L);
        SellerOrder readySellerOrder = new SellerOrder(order, 21L);
        ReflectionTestUtils.setField(readySellerOrder, "id", 8L);
        OrdersItem readyItem = new OrdersItem(readySellerOrder, 102L, "선인장", new BigDecimal("5000"));
        ReflectionTestUtils.setField(readyItem, "id", 6L);
        readySellerOrder.addItems(List.of(readyItem));
        order.addSellerOrders(List.of(readySellerOrder));
        when(ordersRepository.findByIdAndBuyerId(9L, 10L)).thenReturn(Optional.of(order));
        when(sellerOrderRepository.findByOrder_Id(9L))
                .thenReturn(List.of(deliveredItem.getSellerOrder(), readySellerOrder));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L, 8L)))
                .thenReturn(List.of(deliveredItem, readyItem));
        when(shipmentQuery.findBySellerOrderIds(List.of(7L, 8L))).thenReturn(Map.of(
                7L, new ShipmentResult(30L, "DELIVERED", LocalDateTime.of(2026, 10, 12, 13, 0), "TRACK-1",
                        LocalDateTime.of(2026, 10, 12, 10, 0), LocalDateTime.of(2026, 10, 12, 10, 3),
                        LocalDateTime.of(2026, 10, 12, 10, 5)),
                8L, new ShipmentResult(31L, "READY", null)));

        when(productQuery.getProducts(List.of(101L, 102L)))
                .thenReturn(List.of(product(101L, "https://img/monstera.jpg"))); // 102는 사진 없음

        var response = orderReadService.buyerOrder(10L, 9L);

        assertThat(response.sellerOrders()).hasSize(2);
        assertThat(response.sellerOrders().get(0).items().getFirst().thumbnailUrl()).isEqualTo("https://img/monstera.jpg");
        assertThat(response.sellerOrders().get(1).items().getFirst().thumbnailUrl()).isNull();
        assertThat(response.sellerOrders().get(0).shipmentId()).isEqualTo(30L);
        assertThat(response.sellerOrders().get(0).shipmentStatus()).isEqualTo("DELIVERED");
        assertThat(response.sellerOrders().get(0).bookedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 0));
        assertThat(response.sellerOrders().get(0).pickedUpAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 3));
        assertThat(response.sellerOrders().get(0).inTransitAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 5));
        assertThat(response.sellerOrders().get(0).deliveredAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 12, 13, 0));
        assertThat(response.sellerOrders().get(1).bookedAt()).isNull(); // 아직 안 된 단계는 null
        assertThat(response.sellerOrders().get(0).items().getFirst().purchaseConfirmable()).isTrue();
        assertThat(response.sellerOrders().get(1).shipmentStatus()).isEqualTo("READY");
        assertThat(response.sellerOrders().get(1).items().getFirst().purchaseConfirmable()).isFalse();
        verify(shipmentQuery).findBySellerOrderIds(List.of(7L, 8L));
    }

    @Test
    @DisplayName("구매자 상세: 주문상품마다 반품 사유, 설명, 반품 송장번호와 단계 시각, 반품 신청 없는 상품은 null (#606)")
    void buyerDetailShowsReturnTimelinePerItem() {
        Orders order = order(10L);
        order.paid();
        SellerOrder sellerOrder = sellerOrderWith(order, OrderItemStatus.RETURNED, OrderItemStatus.NORMAL);
        List<OrdersItem> items = sellerOrder.getItems();
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 17, 9, 0);
        ReturnRequest completed = new ReturnRequest(items.get(0).getId(), 7L, ReturnReason.DAMAGED, "잎이 찢어졌어요",
                requestedAt);
        completed.approve(ReturnDecidedBy.SELLER, requestedAt.plusHours(3), new BigDecimal("3000"), 50L);
        completed.complete();
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 20, 15, 0);
        ReflectionTestUtils.setField(completed, "updatedAt", completedAt);
        LocalDateTime arrivedAt = LocalDateTime.of(2026, 11, 19, 11, 0);
        when(ordersRepository.findByIdAndBuyerId(9L, 10L)).thenReturn(Optional.of(order));
        when(sellerOrderRepository.findByOrder_Id(9L)).thenReturn(List.of(sellerOrder));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(items);
        when(shipmentQuery.findBySellerOrderIds(List.of(7L))).thenReturn(Map.of(
                7L, new ShipmentResult(30L, "DELIVERED", LocalDateTime.of(2026, 11, 15, 13, 0))));
        when(returnRequestRepository.findBySellerOrderIdIn(List.of(7L))).thenReturn(List.of(completed));
        when(shipmentQuery.findByShipmentIds(List.of(50L))).thenReturn(Map.of(50L,
                new ShipmentResult(50L, "DELIVERED", arrivedAt, "404000000050", null,
                        LocalDateTime.of(2026, 11, 18, 10, 0), LocalDateTime.of(2026, 11, 18, 14, 0))));

        var response = orderReadService.buyerOrder(10L, 9L);

        var returnedItem = response.sellerOrders().getFirst().items().get(0);
        assertThat(returnedItem.status()).isEqualTo(OrderItemStatus.RETURNED);
        assertThat(returnedItem.returnReason()).isEqualTo(ReturnReason.DAMAGED);
        assertThat(returnedItem.returnDetail()).isEqualTo("잎이 찢어졌어요");
        assertThat(returnedItem.returnRequestedAt()).isEqualTo(requestedAt);
        assertThat(returnedItem.returnDeadlineAt()).isNull();
        assertThat(returnedItem.returnDecidedAt()).isEqualTo(requestedAt.plusHours(3));
        assertThat(returnedItem.returnShipmentStatus()).isEqualTo("DELIVERED");
        assertThat(returnedItem.returnTrackingNo()).isEqualTo("404000000050");
        assertThat(returnedItem.returnPickedUpAt()).isEqualTo(LocalDateTime.of(2026, 11, 18, 10, 0));
        assertThat(returnedItem.returnInTransitAt()).isEqualTo(LocalDateTime.of(2026, 11, 18, 14, 0));
        assertThat(returnedItem.returnArrivedAt()).isEqualTo(arrivedAt);
        assertThat(returnedItem.returnedAt()).isEqualTo(completedAt);
        assertThat(returnedItem.purchaseConfirmable()).isFalse();
        var plainItem = response.sellerOrders().getFirst().items().get(1);
        assertThat(plainItem.returnReason()).isNull();
        assertThat(plainItem.returnRequestedAt()).isNull();
        assertThat(plainItem.returnShipmentStatus()).isNull();
        assertThat(plainItem.returnedAt()).isNull();
        assertThat(plainItem.purchaseConfirmable()).isTrue();
    }

    @Test
    void cancelledItemIsNotConfirmableEvenAfterDelivery() {
        Orders order = order(10L);
        order.paid();
        OrdersItem item = item(order, 20L);
        ReflectionTestUtils.setField(item, "status", OrderItemStatus.CANCELLED);
        when(ordersRepository.findByIdAndBuyerId(9L, 10L)).thenReturn(Optional.of(order));
        when(sellerOrderRepository.findByOrder_Id(9L)).thenReturn(List.of(item.getSellerOrder()));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(List.of(item));
        when(shipmentQuery.findBySellerOrderIds(List.of(7L))).thenReturn(Map.of(
                7L, new ShipmentResult(30L, "DELIVERED", null)));

        var response = orderReadService.buyerOrder(10L, 9L);

        assertThat(response.sellerOrders().getFirst().items().getFirst().purchaseConfirmable()).isFalse();
    }

    @Test
    @DisplayName("[S-A07] 구매자는 남의 주문을 조회할 수 없음(404)")
    void buyerCannotReadAnotherBuyersOrder() {
        when(ordersRepository.findByIdAndBuyerId(9L, 11L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderReadService.buyerOrder(11L, 9L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND));
        verify(sellerOrderRepository, never()).findByOrder_Id(any());
    }

    @Test
    void sellerListUsesOnlyOwnSellerOrdersAndBatchLoadsItems() {
        Orders order = order(10L);
        OrdersItem item = item(order, 20L);
        SellerOrder sellerOrder = item.getSellerOrder();
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 10, 20, 12, 0);
        ReflectionTestUtils.setField(sellerOrder, "confirmedAt", confirmedAt);
        when(sellerOrderRepository.findBySellerId(eq(20L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sellerOrder), PageRequest.of(0, 20), 1));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(List.of(item));
        when(productQuery.getProducts(List.of(101L))).thenReturn(List.of(product(101L, "https://img/monstera.jpg")));

        var response = orderReadService.sellerOrders(20L, new OrderListRequest(0, 20));

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.sellerOrderId()).isEqualTo(7L);
            assertThat(summary.itemCount()).isEqualTo(1);
            assertThat(summary.representativeItemName()).isEqualTo("몬스테라");
            assertThat(summary.thumbnailUrl()).isEqualTo("https://img/monstera.jpg");
            assertThat(summary.totalAmount()).isEqualByComparingTo("10000");
            assertThat(summary.confirmedAt()).isEqualTo(confirmedAt);
        });
    }

    @Test
    @DisplayName("[S-A07] 판매자는 남의 판매 주문을 조회할 수 없음(404)")
    void sellerCannotReadAnotherSellersOrder() {
        when(sellerOrderRepository.findByIdAndSellerId(7L, 21L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderReadService.sellerOrder(21L, 7L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.SELLER_ORDER_NOT_FOUND));
        verify(ordersItemRepository, never()).findBySellerOrderIds(any());
    }

    @Test
    void sellerDetailReturnsOnlyItsOwnItemsAndDeliveryAddress() {
        Orders order = order(10L);
        OrdersItem item = item(order, 20L);
        when(sellerOrderRepository.findByIdAndSellerId(7L, 20L))
                .thenReturn(Optional.of(item.getSellerOrder()));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(List.of(item));

        when(settlementQuery.getSettlement(7L)).thenReturn(SellerOrderSettlement.of(null));
        when(productQuery.getProducts(List.of(101L))).thenReturn(List.of(product(101L, "https://img/monstera.jpg")));
        when(memberQuery.getNicknames(List.of(10L))).thenReturn(Map.of(10L, "초록손"));

        var response = orderReadService.sellerOrder(20L, 7L);

        assertThat(response.orderId()).isEqualTo(9L);
        assertThat(response.buyerNickname()).isEqualTo("초록손"); // 주문의 구매자(10L) 닉네임 (#599)
        assertThat(response.recipientName()).isEqualTo("구매자"); // 받는 사람 이름은 그대로 따로
        assertThat(response.totalAmount()).isEqualByComparingTo("10000");
        assertThat(response.shippingFee()).isEqualByComparingTo("3000");
        assertThat(response.items()).singleElement().satisfies(detail -> {
            assertThat(detail.productName()).isEqualTo("몬스테라");
            assertThat(detail.thumbnailUrl()).isEqualTo("https://img/monstera.jpg");
        });
        assertThat(response.address1()).isEqualTo("서울시");
        assertThat(response.settlementStatus()).isEqualTo("NOT_SETTLED");
        assertThat(response.settledAt()).isNull();
        assertThat(response.confirmedAt()).isNull(); // 구매 확정 전 (#626)
        assertThat(response.items()).singleElement().satisfies(detail ->
                assertThat(detail.productId()).isEqualTo(101L));
    }

    @Test
    void sellerDetailUsesActualShipmentAndSettlementData() {
        OrdersItem item = item(order(10L), 20L);
        LocalDateTime settledAt = LocalDateTime.of(2026, 10, 19, 12, 0);
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 10, 18, 9, 0);
        ReflectionTestUtils.setField(item.getSellerOrder(), "confirmedAt", confirmedAt);
        when(sellerOrderRepository.findByIdAndSellerId(7L, 20L))
                .thenReturn(Optional.of(item.getSellerOrder()));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(List.of(item));
        when(shipmentQuery.findBySellerOrderId(7L))
                .thenReturn(new ShipmentResult(30L, "BOOKED", null, "TRACK-123",
                        LocalDateTime.of(2026, 10, 12, 10, 0), null, null));
        when(settlementQuery.getSettlement(7L)).thenReturn(SellerOrderSettlement.of(settledAt));

        var response = orderReadService.sellerOrder(20L, 7L);

        assertThat(response.shipmentStatus()).isEqualTo("BOOKED");
        assertThat(response.trackingNo()).isEqualTo("TRACK-123");
        assertThat(response.bookedAt()).isEqualTo(LocalDateTime.of(2026, 10, 12, 10, 0));
        assertThat(response.pickedUpAt()).isNull();
        assertThat(response.deliveredAt()).isNull();
        assertThat(response.confirmedAt()).isEqualTo(confirmedAt); // 정산 시각과 따로 (#626)
        assertThat(response.settlementStatus()).isEqualTo("COMPLETED");
        assertThat(response.settledAt()).isEqualTo(settledAt);
    }

    @Test
    void publicOrderQueryMapsOrderAndItsSellerItems() {
        Orders order = order(10L);
        OrdersItem item = item(order, 20L);
        when(ordersRepository.findById(9L)).thenReturn(Optional.of(order));
        when(sellerOrderRepository.findByOrder_Id(9L)).thenReturn(List.of(item.getSellerOrder()));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(List.of(item));

        var result = orderReadService.getOrder(9L);

        assertThat(result.buyerId()).isEqualTo(10L);
        assertThat(result.sellerOrders()).singleElement().satisfies(seller ->
                assertThat(seller.items()).singleElement().satisfies(detail ->
                        assertThat(detail.productName()).isEqualTo("몬스테라")));
    }

    @Test
    void ordersStillLoadWhenProductLookupFails() {
        Orders order = order(10L);
        OrdersItem item = item(order, 20L);
        when(ordersRepository.findByBuyerId(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));
        when(ordersItemRepository.findByOrderIds(List.of(9L))).thenReturn(List.of(item));
        when(productQuery.getProducts(List.of(101L))).thenThrow(new IllegalStateException("상품 조회 실패"));

        var response = orderReadService.buyerOrders(10L, new OrderListRequest(null, null));

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.representativeItemName()).isEqualTo("몬스테라");
            assertThat(summary.thumbnailUrl()).isNull(); // 사진만 빠지고 주문 내역은 보인다
        });
    }

    private static ProductResult product(Long productId, String thumbnailUrl) {
        return new ProductResult(productId, 20L, "상품", new BigDecimal("10000"), ProductStatus.SOLD_OUT, false,
                thumbnailUrl, "품종", null);
    }

    @Test
    void shippingInfoMapsOrderAndSellerOrderForShipment() {
        OrdersItem item = item(order(10L), 20L);
        when(sellerOrderRepository.findById(7L)).thenReturn(Optional.of(item.getSellerOrder()));

        var info = orderReadService.getShippingInfo(7L);

        assertThat(info.orderId()).isEqualTo(9L);
        assertThat(info.buyerId()).isEqualTo(10L);
        assertThat(info.sellerId()).isEqualTo(20L);
        assertThat(info.shippingFee()).isEqualByComparingTo("3000");
        assertThat(info.recipientName()).isEqualTo("구매자");
        assertThat(info.recipientPhone()).isEqualTo("010");
        assertThat(info.address1()).isEqualTo("서울시");
        assertThat(info.address2()).isNull();
    }

    @Test
    void shippingInfoFailsWhenSellerOrderDoesNotExist() {
        when(sellerOrderRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderReadService.getShippingInfo(7L))
                .isInstanceOf(BusinessException.class);
    }

    private static Orders order(Long buyerId) {
        Orders order = new Orders("ORD-test", buyerId, "구매자", "010", "12345", "서울시", null, 1);
        ReflectionTestUtils.setField(order, "id", 9L);
        ReflectionTestUtils.setField(order, "createdAt", LocalDateTime.of(2026, 10, 12, 12, 0));
        return order;
    }

    private static OrdersItem item(Orders order, Long sellerId) {
        SellerOrder sellerOrder = new SellerOrder(order, sellerId);
        ReflectionTestUtils.setField(sellerOrder, "id", 7L);
        OrdersItem item = new OrdersItem(sellerOrder, 101L, "몬스테라", new BigDecimal("10000"));
        ReflectionTestUtils.setField(item, "id", 5L);
        sellerOrder.addItems(List.of(item));
        sellerOrder.setShippingFee(new BigDecimal("3000"));
        order.addSellerOrders(List.of(sellerOrder));
        return item;
    }

    @Test
    @DisplayName("판매 목록: 반품 신청 상품이 있으면 returnRequested, 응답 전 반품 중 가장 이른 마감과 그 신청 시각 (#383)")
    void sellerListShowsEarliestPendingReturn() {
        Orders order = order(10L);
        SellerOrder sellerOrder = sellerOrderWith(order, OrderItemStatus.RETURN_REQUESTED,
                OrderItemStatus.RETURN_REQUESTED, OrderItemStatus.RETURN_IN_TRANSIT);
        List<OrdersItem> items = sellerOrder.getItems();
        LocalDateTime earlier = LocalDateTime.of(2026, 11, 18, 9, 0);
        LocalDateTime later = LocalDateTime.of(2026, 11, 19, 9, 0);
        ReturnRequest approved = returnRequest(items.get(2), LocalDateTime.of(2026, 11, 17, 9, 0));
        approved.approve(ReturnDecidedBy.SELLER, LocalDateTime.of(2026, 11, 17, 10, 0), new BigDecimal("3000"), 50L);
        when(sellerOrderRepository.findBySellerId(eq(20L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sellerOrder), PageRequest.of(0, 20), 1));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(items);
        when(returnRequestRepository.findBySellerOrderIdIn(List.of(7L))).thenReturn(List.of(
                returnRequest(items.get(1), later), approved, returnRequest(items.get(0), earlier)));

        var response = orderReadService.sellerOrders(20L, new OrderListRequest(0, 20));

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.returnRequested()).isTrue();
            // 승인된 반품은 더 오래됐어도 마감 계산에서 빠진다
            assertThat(summary.returnRequestedAt()).isEqualTo(earlier);
            assertThat(summary.returnDeadlineAt()).isEqualTo(earlier.plusHours(48));
        });
        verify(returnRequestRepository, times(1)).findBySellerOrderIdIn(any());
    }

    @Test
    @DisplayName("판매 목록: 응답 전 반품이 없으면 returnRequested false, 신청 시각과 마감은 null (#383)")
    void sellerListWithoutPendingReturn() {
        Orders order = order(10L);
        SellerOrder sellerOrder = sellerOrderWith(order, OrderItemStatus.CONFIRMED);
        ReturnRequest rejected = returnRequest(sellerOrder.getItems().getFirst(), LocalDateTime.of(2026, 11, 18, 9, 0));
        rejected.reject(ReturnDecidedBy.SELLER, LocalDateTime.of(2026, 11, 18, 10, 0));
        when(sellerOrderRepository.findBySellerId(eq(20L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sellerOrder), PageRequest.of(0, 20), 1));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(sellerOrder.getItems());
        when(returnRequestRepository.findBySellerOrderIdIn(List.of(7L))).thenReturn(List.of(rejected));

        var response = orderReadService.sellerOrders(20L, new OrderListRequest(0, 20));

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.returnRequested()).isFalse();
            assertThat(summary.returnRequestedAt()).isNull();
            assertThat(summary.returnDeadlineAt()).isNull();
        });
    }

    @Test
    @DisplayName("판매 상세: 주문상품마다 반품 사유, 직접 작성 내용, 신청 시각, 응답 마감(응답 전에만) (#383)")
    void sellerDetailShowsReturnPerItem() {
        Orders order = order(10L);
        SellerOrder sellerOrder = sellerOrderWith(order, OrderItemStatus.RETURN_REQUESTED,
                OrderItemStatus.RETURN_IN_TRANSIT, OrderItemStatus.NORMAL);
        List<OrdersItem> items = sellerOrder.getItems();
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 18, 9, 0);
        LocalDateTime approvedRequestedAt = LocalDateTime.of(2026, 11, 17, 9, 0);
        ReturnRequest pending = new ReturnRequest(items.get(0).getId(), 7L, ReturnReason.OTHER, "잎이 다 시들었어요", requestedAt);
        ReturnRequest approved = new ReturnRequest(items.get(1).getId(), 7L, ReturnReason.DAMAGED, null, approvedRequestedAt);
        approved.approve(ReturnDecidedBy.AUTO, approvedRequestedAt.plusHours(48), new BigDecimal("3000"), 50L);
        when(sellerOrderRepository.findByIdAndSellerId(7L, 20L)).thenReturn(Optional.of(sellerOrder));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(items);
        when(returnRequestRepository.findBySellerOrderIdIn(List.of(7L))).thenReturn(List.of(pending, approved));
        LocalDateTime pickedUpAt = LocalDateTime.of(2026, 11, 19, 10, 0);
        when(shipmentQuery.findByShipmentIds(List.of(50L))).thenReturn(Map.of(50L,
                new ShipmentResult(50L, "PICKED_UP", null, "404000000050", null, pickedUpAt, null)));
        when(settlementQuery.getSettlement(7L)).thenReturn(SellerOrderSettlement.of(null));

        var response = orderReadService.sellerOrder(20L, 7L);

        assertThat(response.items()).hasSize(3);
        var pendingItem = response.items().get(0);
        assertThat(pendingItem.returnReason()).isEqualTo(ReturnReason.OTHER);
        assertThat(pendingItem.returnDetail()).isEqualTo("잎이 다 시들었어요");
        assertThat(pendingItem.returnRequestedAt()).isEqualTo(requestedAt);
        assertThat(pendingItem.returnDeadlineAt()).isEqualTo(requestedAt.plusHours(48));
        var approvedItem = response.items().get(1);
        assertThat(approvedItem.returnReason()).isEqualTo(ReturnReason.DAMAGED);
        assertThat(approvedItem.returnRequestedAt()).isEqualTo(approvedRequestedAt);
        assertThat(approvedItem.returnDeadlineAt()).isNull(); // 승인 뒤에는 마감 없음
        // 반품 타임라인 (#606): 승인 시각, 반품 택배 상태, 수거 시각. 아직 안 된 단계는 null
        assertThat(approvedItem.returnDecidedAt()).isEqualTo(approvedRequestedAt.plusHours(48));
        assertThat(approvedItem.returnShipmentStatus()).isEqualTo("PICKED_UP");
        assertThat(approvedItem.returnTrackingNo()).isEqualTo("404000000050");
        assertThat(approvedItem.returnPickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(approvedItem.returnInTransitAt()).isNull();
        assertThat(approvedItem.returnArrivedAt()).isNull();
        assertThat(approvedItem.returnedAt()).isNull();
        assertThat(pendingItem.returnDecidedAt()).isNull();
        assertThat(pendingItem.returnShipmentStatus()).isNull();
        var plainItem = response.items().get(2);
        assertThat(plainItem.returnReason()).isNull();
        assertThat(plainItem.returnDetail()).isNull();
        assertThat(plainItem.returnRequestedAt()).isNull();
        assertThat(plainItem.returnDeadlineAt()).isNull();
    }

    @Test
    @DisplayName("반품 정보: 반품 신청 1번 + 반품 배송 1번으로 단계 시각을 모은다, 환불 완료 시각은 완료된 신청의 updated_at (#606)")
    void returnTimelinesCollectStepTimesInTwoQueries() {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 11, 17, 9, 0);
        LocalDateTime decidedAt = requestedAt.plusHours(2);
        ReturnRequest pending = new ReturnRequest(1L, 7L, ReturnReason.OTHER, "잎이 시들었어요", requestedAt);
        ReturnRequest inTransit = new ReturnRequest(2L, 7L, ReturnReason.DAMAGED, null, requestedAt);
        inTransit.approve(ReturnDecidedBy.SELLER, decidedAt, new BigDecimal("3000"), 50L);
        ReturnRequest completed = new ReturnRequest(3L, 8L, ReturnReason.NOT_AS_DESCRIBED, null, requestedAt);
        completed.approve(ReturnDecidedBy.AUTO, decidedAt, new BigDecimal("3000"), 51L);
        completed.complete();
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 20, 15, 0);
        ReflectionTestUtils.setField(completed, "updatedAt", completedAt);
        ReturnRequest rejected = new ReturnRequest(4L, 8L, ReturnReason.OTHER, "향이 달라요", requestedAt);
        rejected.reject(ReturnDecidedBy.SELLER, decidedAt);
        LocalDateTime pickedUpAt = LocalDateTime.of(2026, 11, 18, 10, 0);
        LocalDateTime inTransitAt = LocalDateTime.of(2026, 11, 18, 14, 0);
        LocalDateTime arrivedAt = LocalDateTime.of(2026, 11, 19, 11, 0);
        when(returnRequestRepository.findBySellerOrderIdIn(List.of(7L, 8L)))
                .thenReturn(List.of(pending, inTransit, completed, rejected));
        when(shipmentQuery.findByShipmentIds(List.of(50L, 51L))).thenReturn(Map.of(
                50L, new ShipmentResult(50L, "IN_TRANSIT", null, "404000000050", null, pickedUpAt, inTransitAt),
                51L, new ShipmentResult(51L, "DELIVERED", arrivedAt, "404000000051", null, pickedUpAt, inTransitAt)));

        Map<Long, ReturnTimeline> timelines = orderReadService.returnTimelinesByItem(List.of(7L, 8L));

        assertThat(timelines).hasSize(4);
        ReturnTimeline pendingTimeline = timelines.get(1L);
        assertThat(pendingTimeline.requestedAt()).isEqualTo(requestedAt);
        assertThat(pendingTimeline.deadlineAt()).isEqualTo(requestedAt.plusHours(48));
        assertThat(pendingTimeline.decidedAt()).isNull();
        assertThat(pendingTimeline.shipmentStatus()).isNull();
        assertThat(pendingTimeline.returnedAt()).isNull();
        ReturnTimeline inTransitTimeline = timelines.get(2L);
        assertThat(inTransitTimeline.decidedAt()).isEqualTo(decidedAt);
        assertThat(inTransitTimeline.deadlineAt()).isNull();
        assertThat(inTransitTimeline.shipmentStatus()).isEqualTo("IN_TRANSIT");
        assertThat(inTransitTimeline.trackingNo()).isEqualTo("404000000050");
        assertThat(inTransitTimeline.pickedUpAt()).isEqualTo(pickedUpAt);
        assertThat(inTransitTimeline.inTransitAt()).isEqualTo(inTransitAt);
        assertThat(inTransitTimeline.arrivedAt()).isNull();
        assertThat(inTransitTimeline.returnedAt()).isNull();
        ReturnTimeline completedTimeline = timelines.get(3L);
        assertThat(completedTimeline.arrivedAt()).isEqualTo(arrivedAt);
        assertThat(completedTimeline.returnedAt()).isEqualTo(completedAt);
        ReturnTimeline rejectedTimeline = timelines.get(4L);
        assertThat(rejectedTimeline.decidedAt()).isEqualTo(decidedAt); // 거절 시각
        assertThat(rejectedTimeline.shipmentStatus()).isNull();
        assertThat(rejectedTimeline.returnedAt()).isNull();
        verify(shipmentQuery, times(1)).findByShipmentIds(any());
    }

    @Test
    @DisplayName("반품 정보: 반품 신청이 없으면 반품 배송을 조회하지 않는다 (#606)")
    void returnTimelinesSkipShipmentQueryWithoutReturns() {
        when(returnRequestRepository.findBySellerOrderIdIn(List.of(7L))).thenReturn(List.of());

        assertThat(orderReadService.returnTimelinesByItem(List.of(7L))).isEmpty();
        verify(shipmentQuery, never()).findByShipmentIds(any());
    }

    // 판매자 20의 판매자 주문 7. 주문상품 i번째는 id 5 + i, 상태 statuses[i]
    private static SellerOrder sellerOrderWith(Orders order, OrderItemStatus... statuses) {
        SellerOrder sellerOrder = new SellerOrder(order, 20L);
        ReflectionTestUtils.setField(sellerOrder, "id", 7L);
        List<OrdersItem> items = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            OrdersItem item = new OrdersItem(sellerOrder, 101L + i, "상품" + (i + 1), new BigDecimal("10000"));
            ReflectionTestUtils.setField(item, "id", 5L + i);
            ReflectionTestUtils.setField(item, "status", statuses[i]);
            items.add(item);
        }
        sellerOrder.addItems(items);
        sellerOrder.setShippingFee(new BigDecimal("3000"));
        order.addSellerOrders(List.of(sellerOrder));
        return sellerOrder;
    }

    private static ReturnRequest returnRequest(OrdersItem item, LocalDateTime requestedAt) {
        return new ReturnRequest(item.getId(), 7L, ReturnReason.DAMAGED, null, requestedAt);
    }
}
