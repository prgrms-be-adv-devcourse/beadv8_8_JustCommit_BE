package com.justcommit.backend.market.client.product;

import com.justcommit.backend.common.client.exception.InternalApiErrorException;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.market.order.domain.OrderErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
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

    // shortcut: 판매 완료 · 예약 해제 · 판매 재개는 product에 알리지 않아 상품이 PAYMENT_PENDING · SOLD_OUT에 머문다, #277에서 Outbox 이벤트로 바꾸며 이 세 메서드를 지운다
    @Override
    public boolean release(Long orderId) {
        log.warn("상품 예약 해제를 건너뜀 (#277 전): orderId={}", orderId);
        return true;
    }

    @Override
    public boolean markSoldOut(Long orderId) {
        log.warn("상품 판매 완료를 건너뜀 (#277 전): orderId={}", orderId);
        return true;
    }

    @Override
    public boolean releaseItem(Long productId) {
        log.warn("상품 판매 재개를 건너뜀 (#277 전): productId={}", productId);
        return true;
    }
}
