package com.justcommit.backend.market.cart.application;


import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.market.cart.domain.Cart;
import com.justcommit.backend.market.cart.domain.CartItem;
import com.justcommit.backend.market.cart.infrastructure.CartItemRepository;
import com.justcommit.backend.market.cart.infrastructure.CartRepository;
import com.justcommit.backend.market.cart.presentation.CartItemResponse;
import com.justcommit.backend.market.cart.presentation.CartResponse;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductQuery productQuery;

    private Cart findByMemberId(long memberId) {
        Cart cart = cartRepository.findByMemberId(memberId).orElse(null);
        if (cart == null) {
            cart = create(memberId);
        }
        return cart;
    }

    @Transactional
    public CartResponse find(Long memberId) {
        Cart cart = findByMemberId(memberId);
        List<ProductResult> products = productQuery.getProducts(cart.getItems().stream().map(CartItem::getProductId).toList());
        Map<Long, ProductResult> productMap = products.stream()
                .collect(Collectors.toMap(ProductResult::productId, Function.identity()));

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
                            item.getPrice(),
                            product.price(),
                            item.getPrice().compareTo(product.price()) != 0,
                            product.isPurchasable(),
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
    public void removeItemFromCart(long memberId, long productId) {
        Cart cart = findByMemberId(memberId);

        CartItem cartItem = cart.getItems().stream().filter(i -> i.getProductId().equals(productId)).findFirst().orElse(null);
        if (cartItem == null) {
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "장바구니에 해당 상품이 없습니다.");
        }
        cart.getItems().remove(cartItem);
        cartItemRepository.delete(cartItem);
    }

    private Cart create(Long memberId) {
        return cartRepository.save(new Cart(memberId));
    }

}
