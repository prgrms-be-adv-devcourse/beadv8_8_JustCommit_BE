package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.domain.OrdersItem;
import com.justcommit.backend.market.order.domain.SellerOrder;
import com.justcommit.backend.market.order.infrastructure.OrdersItemRepository;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.infrastructure.SellerOrderRepository;
import com.justcommit.backend.market.order.presentation.OrderListRequest;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderReadServiceTest {

    @Mock OrdersRepository ordersRepository;
    @Mock SellerOrderRepository sellerOrderRepository;
    @Mock OrdersItemRepository ordersItemRepository;
    @InjectMocks OrderReadService orderReadService;

    @Test
    void buyerListUsesPageAndLoadsItemNamesInOneBatch() {
        Orders order = order(10L);
        OrdersItem item = item(order, 20L);
        when(ordersRepository.findByBuyerId(eq(10L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 20), 1));
        when(ordersItemRepository.findByOrderIds(List.of(9L))).thenReturn(List.of(item));

        var response = orderReadService.buyerOrders(10L, new OrderListRequest(null, null));

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.orderId()).isEqualTo(9L);
            assertThat(summary.representativeItemName()).isEqualTo("몬스테라");
        });
        assertThat(response.totalElements()).isEqualTo(1);
        verify(ordersItemRepository).findByOrderIds(List.of(9L));
    }

    @Test
    void buyerDetailLoadsOnlyOwnedOrder() {
        Orders order = order(10L);
        OrdersItem item = item(order, 20L);
        SellerOrder sellerOrder = item.getSellerOrder();
        when(ordersRepository.findByIdAndBuyerId(9L, 10L)).thenReturn(Optional.of(order));
        when(sellerOrderRepository.findByOrder_Id(9L)).thenReturn(List.of(sellerOrder));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(List.of(item));

        var response = orderReadService.buyerOrder(10L, 9L);

        assertThat(response.sellerOrders()).singleElement().satisfies(seller -> {
            assertThat(seller.sellerId()).isEqualTo(20L);
            assertThat(seller.items()).singleElement().satisfies(detail ->
                    assertThat(detail.productName()).isEqualTo("몬스테라"));
        });
        verify(ordersRepository).findByIdAndBuyerId(9L, 10L);
    }

    @Test
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
        when(sellerOrderRepository.findBySellerId(eq(20L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sellerOrder), PageRequest.of(0, 20), 1));
        when(ordersItemRepository.findBySellerOrderIds(List.of(7L))).thenReturn(List.of(item));

        var response = orderReadService.sellerOrders(20L, new OrderListRequest(0, 20));

        assertThat(response.content()).singleElement().satisfies(summary -> {
            assertThat(summary.sellerOrderId()).isEqualTo(7L);
            assertThat(summary.itemCount()).isEqualTo(1);
            assertThat(summary.representativeItemName()).isEqualTo("몬스테라");
            assertThat(summary.totalAmount()).isEqualByComparingTo("13000");
        });
    }

    @Test
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

        var response = orderReadService.sellerOrder(20L, 7L);

        assertThat(response.orderId()).isEqualTo(9L);
        assertThat(response.address1()).isEqualTo("서울시");
        assertThat(response.items()).singleElement().satisfies(detail ->
                assertThat(detail.productId()).isEqualTo(101L));
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
}
