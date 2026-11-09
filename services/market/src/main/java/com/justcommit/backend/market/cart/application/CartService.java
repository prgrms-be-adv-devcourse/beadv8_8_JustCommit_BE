package com.justcommit.backend.market.cart.application;


import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.cart.infrastructure.CartItemRepository;
import com.justcommit.backend.market.cart.infrastructure.CartRepository;
import com.justcommit.backend.market.cart.presentation.CartItemPriceUpdateRequest;
import com.justcommit.backend.market.cart.presentation.CartItemResponse;
import com.justcommit.backend.market.cart.presentation.CartResponse;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import com.justcommit.backend.market.client.member.MemberQuery;
import com.justcommit.backend.market.client.product.ProductQuery;
import com.justcommit.backend.market.client.product.ProductResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductQuery productQuery;
    private final MemberQuery memberQuery;

    private Cart findByMemberId(long memberId) {
        Cart cart = cartRepository.findByMemberId(memberId).orElse(null);
        if (cart == null) {
            cart = create(memberId);
        }
        return cart;
    }

    @Transactional(readOnly = true)
    public List<CartItem> getSelectCartItems(long memberId, List<Long> cartItemIds) {
        validateCartItemIds(cartItemIds);
        Cart cart = cartRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "장바구니를 찾을 수 없습니다."));
        Map<Long, CartItem> itemsById = cart.getItems().stream()
                .collect(Collectors.toMap(CartItem::getId, Function.identity()));
        return cartItemIds.stream().map(id -> {
            CartItem item = itemsById.get(id);
            if (item == null) {
                throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "장바구니 항목을 찾을 수 없습니다.");
            }
            return item;
        }).toList();
    }

    @Transactional
    public void removeSelectedItems(long memberId, List<Long> cartItemIds) {
        validateCartItemIds(cartItemIds);
        Cart cart = cartRepository.findByMemberId(memberId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "장바구니를 찾을 수 없습니다."));
        Set<Long> selectedIds = Set.copyOf(cartItemIds);
        List<CartItem> selected = cart.getItems().stream()
                .filter(item -> selectedIds.contains(item.getId()))
                .toList();
        if (selected.size() != selectedIds.size()) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "장바구니 항목을 찾을 수 없습니다.");
        }
        cart.getItems().removeAll(selected);
        cartItemRepository.deleteAll(selected);
    }

    private void validateCartItemIds(List<Long> cartItemIds) {
        if (cartItemIds == null || cartItemIds.isEmpty() || cartItemIds.stream().anyMatch(id -> id == null || id <= 0)
                || cartItemIds.size() != cartItemIds.stream().distinct().count()) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA, "장바구니 항목 ID를 확인해 주세요.");
        }
    }

    @Transactional
    public CartResponse find(Long memberId) {
        Cart cart = findByMemberId(memberId);
        return toResponse(cart, productQuery.getProducts(productIds(cart)), memberId);
    }

    // 고른 항목이 모두 구매 가능하고 현재 가격이 동의한 가격과 모두 같을 때만 저장 가격을 바꾼다. 하나라도 아니면 아무것도 안 바꾼다
    @Transactional
    public CartResponse updatePrices(long memberId, List<CartItemPriceUpdateRequest.Item> priceItems) {
        List<CartItem> selected = getSelectCartItems(memberId,
                priceItems.stream().map(CartItemPriceUpdateRequest.Item::cartItemId).toList());
        Cart cart = selected.getFirst().getCart();
        List<ProductResult> products = productQuery.getProducts(productIds(cart));
        Map<Long, ProductResult> productMap = products.stream()
                .collect(Collectors.toMap(ProductResult::productId, Function.identity()));

        if (selected.stream().anyMatch(item -> !isPurchasable(productMap.get(item.getProductId()), memberId))) {
            throw new BusinessException(OrderErrorCode.PRODUCT_UNAVAILABLE);
        }
        Map<Long, BigDecimal> expectedPrices = priceItems.stream().collect(Collectors.toMap(
                CartItemPriceUpdateRequest.Item::cartItemId, CartItemPriceUpdateRequest.Item::expectedPrice));
        if (selected.stream().anyMatch(item ->
                productMap.get(item.getProductId()).price().compareTo(expectedPrices.get(item.getId())) != 0)) {
            throw new BusinessException(OrderErrorCode.PRICE_CHANGED, "상품 가격이 다시 변경되었습니다. 장바구니를 다시 조회해 주세요.");
        }

        selected.forEach(item -> item.changePrice(productMap.get(item.getProductId()).price()));
        return toResponse(cart, products, memberId);
    }

    private static List<Long> productIds(Cart cart) {
        return cart.getItems().stream().map(CartItem::getProductId).toList();
    }

    private static boolean isPurchasable(ProductResult product, long memberId) {
        return product != null && product.isPurchasable() && product.sellerId() != null
                && !product.sellerId().equals(memberId);
    }

    private CartResponse toResponse(Cart cart, List<ProductResult> products, long memberId) {
        Map<Long, ProductResult> productMap = products.stream()
                .collect(Collectors.toMap(ProductResult::productId, Function.identity()));
        List<Long> sellerIds = products.stream().map(ProductResult::sellerId)
                .filter(id -> id != null).distinct().toList();
        Map<Long, String> sellerNames = sellerIds.isEmpty() ? Map.of() : memberQuery.getNicknames(sellerIds);

        List<CartItemResponse> items = cart.getItems().stream()
                .map(item -> {
                    ProductResult product = productMap.get(item.getProductId());
                    if (product == null) {
                        //상품정합성 깨졌을때만 발생
                        throw new IllegalStateException(
                                "CartItem " + item.getId() + " references missing product " + item.getProductId()
                        );
                    }
                    return new CartItemResponse(
                            item.getId(),
                            item.getProductId(),
                            product.title(),
                            product.thumbnailUrl(),
                            product.speciesName(),
                            product.sellerId(),
                            sellerNames.get(product.sellerId()),
                            item.getPrice(),
                            product.price(),
                            item.getPrice().compareTo(product.price()) != 0,
                            isPurchasable(product, memberId),
                            product.status().name(),
                            product.hidden(),
                            item.getCreatedAt());
                })
                .toList();
        BigDecimal nowTotalAmount = items.stream().map(CartItemResponse::nowPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartResponse(cart.getId(), items, cart.getTotalPrice(), nowTotalAmount);
    }

    @Transactional
    public Long addItemToCart(long memberId, long productId) {
        Cart cart = findByMemberId(memberId);

        boolean alreadyAdded = cart.getItems().stream()
                .anyMatch(item -> item.getProductId().equals(productId));

        if (alreadyAdded) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA, "이미 추가된 상품입니다.");
        }

        ProductResult product = productQuery.getProduct(productId);

        if (product.sellerId() != null && product.sellerId().equals(memberId)) {
            throw new BusinessException(OrderErrorCode.OWN_PRODUCT);
        }
        if (!product.isPurchasable()) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA, "구매가 불가능한 상품입니다.");
        }

        CartItem cartItem = new CartItem(cart, productId, product.price());
        cart.addItem(cartItem);
        cartItemRepository.save(cartItem);
        cartRepository.save(cart);

        return cartItem.getId();
    }

    @Transactional
    public void restoreItem(long memberId, long productId, BigDecimal price) {
        Cart cart = findByMemberId(memberId);
        if (cart.getItems().stream().anyMatch(item -> item.getProductId().equals(productId))) {
            return;
        }
        // 주문 당시 가격을 담아 두고, 다음 결제 시 현재 가격과 구매 가능 여부를 다시 확인한다.
        CartItem item = new CartItem(cart, productId, price);
        cart.addItem(item);
        cartItemRepository.save(item);
    }

    @Transactional
    public void removeItemFromCart(long memberId, long productId) {
        Cart cart = findByMemberId(memberId);

        CartItem cartItem = cart.getItems().stream().filter(i -> i.getProductId().equals(productId)).findFirst().orElse(null);
        if (cartItem == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "장바구니에 해당 상품이 없습니다.");
        }
        cart.getItems().remove(cartItem);
        cartItemRepository.delete(cartItem);
    }

    public void createCart(Long memberId) {
        if (cartRepository.countByMemberId(memberId) < 1) {
            create(memberId);
        }
    }

    private Cart create(Long memberId) {
        return cartRepository.save(new Cart(memberId));
    }

}
