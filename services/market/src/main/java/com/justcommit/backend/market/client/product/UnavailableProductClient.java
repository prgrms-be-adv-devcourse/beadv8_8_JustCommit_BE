package com.justcommit.backend.market.client.product;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;

// shortcut: product 내부 API 전환 전 임시 구현이라 호출하면 503, #276(조회 · 예약)과 #277(판매 완료 · 해제 · 재개)에서 교체
@Component
class UnavailableProductClient implements ProductQuery, ProductUseCase {

    @Override
    public ProductResult getProduct(Long productId) {
        throw unavailable();
    }

    @Override
    public List<ProductResult> getProducts(List<Long> productIds) {
        throw unavailable();
    }

    @Override
    public boolean reserve(Long orderId, List<Long> productIds) {
        throw unavailable();
    }

    @Override
    public boolean release(Long orderId) {
        throw unavailable();
    }

    @Override
    public boolean markSoldOut(Long orderId) {
        throw unavailable();
    }

    @Override
    public boolean releaseItem(Long productId) {
        throw unavailable();
    }

    private static BusinessException unavailable() {
        return new BusinessException(CommonErrorCode.INTERNAL_API_UNAVAILABLE);
    }
}
