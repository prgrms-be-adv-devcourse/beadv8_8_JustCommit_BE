package com.justcommit.backend.market.client.product;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
class ProductClient implements ProductQuery, ProductUseCase {

    private final ProductApi productApi;

    // 단건 API는 따로 두지 않고 여러 건 조회를 id 하나로 부른다
    @Override
    public ProductResult getProduct(Long productId) {
        return getProducts(List.of(productId)).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(OrderErrorCode.PRODUCT_NOT_FOUND));
    }

    @Override
    public List<ProductResult> getProducts(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return List.of();
        }
        return productApi.getProducts(productIds).data();
    }

    @Override
    public void reserve(Long orderId, List<ReservationItem> items) {
        try {
            productApi.reserve(orderId, new ProductApi.ReservationRequest(orderId, items));
        } catch (InternalApiErrorException e) {
            if (e.status() != 409) {
                throw e;
            }
            throw new BusinessException("PRICE_CHANGED".equals(e.code())
                    ? OrderErrorCode.PRICE_CHANGED
                    : OrderErrorCode.PRODUCT_UNAVAILABLE);
        }
    }
}
