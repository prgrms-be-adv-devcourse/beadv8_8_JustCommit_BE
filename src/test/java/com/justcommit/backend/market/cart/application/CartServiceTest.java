package com.justcommit.backend.market.cart.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.cart.infrastructure.CartItemRepository;
import com.justcommit.backend.market.cart.infrastructure.CartRepository;
import com.justcommit.backend.market.cart.presentation.CartResponse;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock ProductQuery productQuery;
    @Mock MemberQuery memberQuery;
    @InjectMocks CartService cartService;

    @Test
    void createsCartForMemberWithoutOne() {
        when(cartRepository.countByMemberId(10L)).thenReturn(0);

        cartService.createCart(10L);

        ArgumentCaptor<Cart> savedCart = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(savedCart.capture());
        assertThat(savedCart.getValue().getMemberId()).isEqualTo(10L);
    }

    @Test
    void keepsExistingCartWhenRegistrationEventIsRepeated() {
        when(cartRepository.countByMemberId(10L)).thenReturn(0, 1);

        cartService.createCart(10L);
        cartService.createCart(10L);

        verify(cartRepository, times(1)).save(any(Cart.class));
    }

    @Test
    void doesNotCreateCartIfMemberAlreadyHasOne() {
        when(cartRepository.countByMemberId(10L)).thenReturn(1);

        cartService.createCart(10L);

        verify(cartRepository, never()).save(any(Cart.class));
    }

    @Test
    void createsCartWhenMemberHasNoCart() {
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(invocation -> {
            Cart cart = invocation.getArgument(0);
            ReflectionTestUtils.setField(cart, "id", 1L);
            return cart;
        });
        when(productQuery.getProducts(List.of())).thenReturn(List.of());

        CartResponse response = cartService.find(10L);

        assertThat(response.cartId()).isEqualTo(1L);
        assertThat(response.items()).isEmpty();
        assertThat(response.totalAmount()).isEqualByComparingTo("0");
        assertThat(response.nowTotalAmount()).isEqualByComparingTo("0");
    }

    @Test
    void showsSavedAndCurrentPricesAndPurchaseAvailability() {
        Cart cart = new Cart(10L);
        addItem(cart, 1L, 101L, "10000");
        addItem(cart, 2L, 102L, "20000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));
        when(productQuery.getProducts(List.of(101L, 102L))).thenReturn(List.of(
                product(101L, "몬스테라", "12000", ProductStatus.ACTIVE),
                product(102L, "품절된 식물", "20000", ProductStatus.SOLD_OUT)));
        when(memberQuery.getNicknames(List.of(20L))).thenReturn(Map.of(20L, "판매자"));

        CartResponse response = cartService.find(10L);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).title()).isEqualTo("몬스테라");
        assertThat(response.items().get(0).price()).isEqualByComparingTo("10000");
        assertThat(response.items().get(0).nowPrice()).isEqualByComparingTo("12000");
        assertThat(response.items().get(0).isChangedPrice()).isTrue();
        assertThat(response.items().get(0).isPurchasable()).isTrue();
        assertThat(response.items().get(0).sellerName()).isEqualTo("판매자");
        assertThat(response.items().get(0).status()).isEqualTo("ACTIVE");
        assertThat(response.items().get(1).isChangedPrice()).isFalse();
        assertThat(response.items().get(1).isPurchasable()).isFalse();
        assertThat(response.totalAmount()).isEqualByComparingTo("30000");
        assertThat(response.nowTotalAmount()).isEqualByComparingTo("32000");
        verify(memberQuery).getNicknames(List.of(20L));
    }

    @Test
    void missingSellerNameDoesNotBlockCartAndOwnProductCannotBePurchased() {
        Cart cart = new Cart(10L);
        addItem(cart, 1L, 101L, "10000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));
        when(productQuery.getProducts(List.of(101L))).thenReturn(List.of(
                new ProductResult(101L, 10L, "내 상품", new BigDecimal("10000"),
                        ProductStatus.ACTIVE, false, "thumbnail", null)));
        when(memberQuery.getNicknames(List.of(10L))).thenReturn(Map.of());

        CartResponse response = cartService.find(10L);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.sellerName()).isNull();
            assertThat(item.thumbnailUrl()).isEqualTo("thumbnail");
            assertThat(item.sellerId()).isEqualTo(10L);
            assertThat(item.isPurchasable()).isFalse();
        });
    }

    @Test
    void rejectsOwnProductWhenAddingToCart() {
        Cart cart = new Cart(10L);
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));
        when(productQuery.getProduct(101L)).thenReturn(new ProductResult(101L, 10L, "내 상품",
                new BigDecimal("10000"), ProductStatus.ACTIVE, false, null, null));

        assertThatThrownBy(() -> cartService.addItemToCart(10L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.OWN_PRODUCT));
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void rejectsProductMissingFromBatchLookup() {
        Cart cart = new Cart(10L);
        addItem(cart, 1L, 101L, "10000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));
        when(productQuery.getProducts(List.of(101L))).thenReturn(List.of());

        assertThatThrownBy(() -> cartService.find(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("references missing product 101");
    }

    @Test
    void addsProductWithItsCurrentPrice() {
        Cart cart = new Cart(10L);
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));
        when(productQuery.getProduct(101L)).thenReturn(product(101L, "몬스테라", "12000", ProductStatus.ACTIVE));
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(invocation -> {
            CartItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 7L);
            return item;
        });

        Long cartItemId = cartService.addItemToCart(10L, 101L);

        assertThat(cartItemId).isEqualTo(7L);
        assertThat(cart.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getCart()).isSameAs(cart);
            assertThat(item.getProductId()).isEqualTo(101L);
            assertThat(item.getPrice()).isEqualByComparingTo("12000");
            verify(cartItemRepository).save(item);
        });
    }

    @Test
    void rejectsDuplicateProductBeforeSaving() {
        Cart cart = new Cart(10L);
        addItem(cart, 1L, 101L, "10000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.addItemToCart(10L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.BAD_REQUEST_DATA));
        verify(productQuery, never()).getProduct(any());
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void rejectsProductThatCannotBePurchased() {
        Cart cart = new Cart(10L);
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));
        when(productQuery.getProduct(101L)).thenReturn(product(101L, "품절된 식물", "12000", ProductStatus.SOLD_OUT));

        assertThatThrownBy(() -> cartService.addItemToCart(10L, 101L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("구매가 불가능한 상품");
        assertThat(cart.getItems()).isEmpty();
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void removesOnlyItemFromMembersCart() {
        Cart cart = new Cart(10L);
        CartItem item = addItem(cart, 1L, 101L, "10000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));

        cartService.removeItemFromCart(10L, 101L);

        assertThat(cart.getItems()).isEmpty();
        verify(cartItemRepository).delete(item);
    }

    @Test
    void cannotRemoveItemFromAnotherMembersCart() {
        Cart anotherMembersCart = new Cart(20L);
        addItem(anotherMembersCart, 1L, 101L, "10000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(new Cart(10L)));

        assertThatThrownBy(() -> cartService.removeItemFromCart(10L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        assertThat(anotherMembersCart.getItems()).hasSize(1);
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void selectsOnlyRequestedCartItemIdsInRequestOrder() {
        Cart cart = new Cart(10L);
        CartItem first = addItem(cart, 1L, 101L, "10000");
        addItem(cart, 2L, 102L, "20000");
        CartItem third = addItem(cart, 3L, 103L, "30000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));

        List<CartItem> selected = cartService.getSelectCartItems(10L, List.of(3L, 1L));

        assertThat(selected).containsExactly(third, first);
    }

    @Test
    void rejectsEmptyAndDuplicateCartItemIds() {
        assertThatThrownBy(() -> cartService.getSelectCartItems(10L, List.of()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cartService.getSelectCartItems(10L, List.of(1L, 1L)))
                .isInstanceOf(BusinessException.class);
        verify(cartRepository, never()).findByMemberId(anyLong());
    }

    @Test
    void rejectsMissingOrAnotherMembersCartItemId() {
        Cart cart = new Cart(10L);
        addItem(cart, 1L, 101L, "10000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.getSelectCartItems(10L, List.of(2L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void removesOnlySelectedCartItemsAfterOrder() {
        Cart cart = new Cart(10L);
        CartItem first = addItem(cart, 1L, 101L, "10000");
        CartItem second = addItem(cart, 2L, 102L, "20000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));

        cartService.removeSelectedItems(10L, List.of(1L));

        assertThat(cart.getItems()).containsExactly(second);
        verify(cartItemRepository).deleteAll(List.of(first));
    }

    @Test
    void doesNotRemoveAnyCartItemIfSelectionIsInvalid() {
        Cart cart = new Cart(10L);
        CartItem item = addItem(cart, 1L, 101L, "10000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));

        assertThatThrownBy(() -> cartService.removeSelectedItems(10L, List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class);

        assertThat(cart.getItems()).containsExactly(item);
        verify(cartItemRepository, never()).deleteAll(any());
    }

    @Test
    void restoresExpiredOrderItemAtItsOriginalPrice() {
        Cart cart = new Cart(10L);
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));

        cartService.restoreItem(10L, 101L, new BigDecimal("10000"));

        assertThat(cart.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(101L);
            assertThat(item.getPrice()).isEqualByComparingTo("10000");
            verify(cartItemRepository).save(item);
        });
        verify(productQuery, never()).getProduct(any());
    }

    @Test
    void skipsAlreadyRestoredProduct() {
        Cart cart = new Cart(10L);
        addItem(cart, 1L, 101L, "12000");
        when(cartRepository.findByMemberId(10L)).thenReturn(Optional.of(cart));

        cartService.restoreItem(10L, 101L, new BigDecimal("10000"));

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().getFirst().getPrice()).isEqualByComparingTo("12000");
        verify(cartItemRepository, never()).save(any());
    }

    private static CartItem addItem(Cart cart, long cartItemId, long productId, String price) {
        CartItem item = new CartItem(cart, productId, new BigDecimal(price));
        ReflectionTestUtils.setField(item, "id", cartItemId);
        cart.addItem(item);
        return item;
    }

    private static ProductResult product(long id, String title, String price, ProductStatus status) {
        return new ProductResult(id, 20L, title, new BigDecimal(price), status, false, null, null);
    }
}
