package com.justcommit.backend.market.order.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.OrderStatus;
import com.justcommit.backend.market.cart.application.CartService;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.order.domain.Orders;
import com.justcommit.backend.market.order.infrastructure.OrdersRepository;
import com.justcommit.backend.market.order.presentation.CartOrderCreateRequest;
import com.justcommit.backend.market.order.presentation.OrderCreateResponse;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrdersRepository ordersRepository;
    @Mock CartService cartService;
    @Mock ProductQuery productQuery;
    @InjectMocks OrderService orderService;

    @Test
    void stopsBeforeProductLookupWhenCartSelectionIsInvalid() {
        CartOrderCreateRequest request = request(1L);
        when(cartService.getSelectCartItems(10L, List.of(1L)))
                .thenThrow(new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND));

        assertThatThrownBy(() -> orderService.create(10L, request))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));

        verify(productQuery, never()).getProducts(any());
        verify(ordersRepository, never()).saveAndFlush(any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    @Test
    void createsSingleItemOrderWithSnapshotsAndRemovesSelectedItem() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        when(ordersRepository.saveAndFlush(any(Orders.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderCreateResponse response = orderService.create(10L, request(1L));

        ArgumentCaptor<Orders> orderCaptor = ArgumentCaptor.forClass(Orders.class);
        verify(ordersRepository).saveAndFlush(orderCaptor.capture());
        Orders order = orderCaptor.getValue();
        assertThat(order.getOrderNo()).hasSize(26).startsWith("ORD-");
        byte[] uuidBytes = Base64.getUrlDecoder().decode(order.getOrderNo().substring(4));
        assertThat(uuidBytes).hasSize(16);
        ByteBuffer uuidBuffer = ByteBuffer.wrap(uuidBytes);
        UUID uuid = new UUID(uuidBuffer.getLong(), uuidBuffer.getLong());
        assertThat(uuid.version()).isEqualTo(4);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getBuyerId()).isEqualTo(10L);
        assertThat(order.getRecipientName()).isEqualTo("구매자");
        assertThat(order.getRecipientPhone()).isEqualTo("01012345678");
        assertThat(order.getZipcode()).isEqualTo("12345");
        assertThat(order.getItemCount()).isEqualTo(1);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("10000");
        assertThat(order.getSellerOrders()).singleElement().satisfies(sellerOrder -> {
            assertThat(sellerOrder.getSellerId()).isEqualTo(20L);
            assertThat(sellerOrder.getShippingFee()).isEqualByComparingTo("0");
            assertThat(sellerOrder.getItems()).singleElement().satisfies(item -> {
                assertThat(item.getProductId()).isEqualTo(101L);
                assertThat(item.getProductName()).isEqualTo("몬스테라");
                assertThat(item.getPrice()).isEqualByComparingTo("10000");
            });
        });
        assertThat(response.totalAmount()).isEqualByComparingTo("10000");
        verify(productQuery).getProducts(List.of(101L));
        verify(cartService).removeSelectedItems(10L, List.of(1L));
    }

    @Test
    void groupsMultipleItemsBySellerAndSumsProductPrices() {
        stubLookup(List.of(selected(1L, 101L, "10000"), selected(2L, 102L, "20000"),
                        selected(3L, 103L, "5000")),
                List.of(product(101L, 20L, "상품1", "10000", true),
                        product(102L, 30L, "상품2", "20000", true),
                        product(103L, 20L, "상품3", "5000", true)));
        when(ordersRepository.saveAndFlush(any(Orders.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderCreateResponse response = orderService.create(10L, request(1L, 2L, 3L));

        ArgumentCaptor<Orders> orderCaptor = ArgumentCaptor.forClass(Orders.class);
        verify(ordersRepository).saveAndFlush(orderCaptor.capture());
        Orders order = orderCaptor.getValue();
        assertThat(order.getSellerOrders()).hasSize(2);
        assertThat(order.getSellerOrders().getFirst().getItems()).hasSize(2);
        assertThat(order.getSellerOrders().getFirst().getTotalAmount()).isEqualByComparingTo("15000");
        assertThat(order.getSellerOrders().getLast().getItems()).hasSize(1);
        assertThat(order.getSellerOrders().getLast().getTotalAmount()).isEqualByComparingTo("20000");
        assertThat(response.totalAmount()).isEqualByComparingTo("35000");
        assertThat(response.itemCount()).isEqualTo(3);
        verify(productQuery).getProducts(List.of(101L, 102L, 103L));
        verify(cartService).removeSelectedItems(10L, List.of(1L, 2L, 3L));
    }

    @Test
    void generatesDifferentOrderNumbersForSeparateOrders() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        when(ordersRepository.saveAndFlush(any(Orders.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderCreateResponse first = orderService.create(10L, request(1L));
        OrderCreateResponse second = orderService.create(10L, request(1L));

        assertThat(first.orderNo()).isNotEqualTo(second.orderNo());
    }

    @Test
    void rejectsUnavailableProductBeforeSavingOrRemovingCartItems() {
        assertRejected(OrderErrorCode.PRODUCT_UNAVAILABLE,
                product(101L, 20L, "품절", "10000", false));
    }

    @Test
    void rejectsOwnProductBeforeSavingOrRemovingCartItems() {
        assertRejected(OrderErrorCode.OWN_PRODUCT,
                product(101L, 10L, "내 상품", "10000", true));
    }

    @Test
    void rejectsChangedPriceBeforeSavingOrRemovingCartItems() {
        assertRejected(OrderErrorCode.PRICE_CHANGED,
                product(101L, 20L, "가격 변경", "12000", true));
    }

    @Test
    void rejectsMissingProductBeforeSavingOrRemovingCartItems() {
        stubLookup(List.of(selected(1L, 101L, "10000")), List.of());

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.CHECKOUT_DATA_MISMATCH));
        verify(ordersRepository, never()).saveAndFlush(any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    @Test
    void preservesCartItemsWhenOrderSaveFails() {
        stubLookup(List.of(selected(1L, 101L, "10000")),
                List.of(product(101L, 20L, "몬스테라", "10000", true)));
        when(ordersRepository.saveAndFlush(any(Orders.class))).thenThrow(new IllegalStateException("DB write failed"));

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOf(IllegalStateException.class);

        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    private void assertRejected(OrderErrorCode expected, ProductResult product) {
        stubLookup(List.of(selected(1L, 101L, "10000")), List.of(product));

        assertThatThrownBy(() -> orderService.create(10L, request(1L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
        verify(ordersRepository, never()).saveAndFlush(any());
        verify(cartService, never()).removeSelectedItems(any(Long.class), any());
    }

    private static CartOrderCreateRequest request(Long... cartItemIds) {
        return new CartOrderCreateRequest(List.of(cartItemIds), "구매자", "01012345678", "12345", "서울시", "101호");
    }

    private void stubLookup(List<CartItem> selectedItems, List<ProductResult> products) {
        List<Long> cartItemIds = selectedItems.stream().map(CartItem::getId).toList();
        List<Long> productIds = selectedItems.stream().map(CartItem::getProductId).toList();
        when(cartService.getSelectCartItems(10L, cartItemIds)).thenReturn(selectedItems);
        when(productQuery.getProducts(productIds)).thenReturn(products);
    }

    private static CartItem selected(long id, long productId, String price) {
        CartItem item = new CartItem(new Cart(10L), productId, money(price));
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private static ProductResult product(long id, long sellerId, String name, String price, boolean purchasable) {
        return new ProductResult(id, sellerId, name, money(price),
                purchasable ? ProductStatus.ACTIVE : ProductStatus.SOLD_OUT, false, null);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
