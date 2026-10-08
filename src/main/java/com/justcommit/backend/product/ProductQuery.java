package com.justcommit.backend.product;

import java.util.List;

public interface ProductQuery {

    /**
     * 상품 한 건 조회.
     *
     * @throws com.justcommit.backend.common.exception.BusinessException 상품이 없으면 PRODUCT_NOT_FOUND
     */
    ProductResult getProduct(Long productId);

    /**
     * 여러 상품 한 번에 조회 (장바구니, 주문서 작성용)
     * 존재하지 않는 ID는 결과에서 제외된다
     */
    List<ProductResult> getProducts(List<Long> productIds);
}
