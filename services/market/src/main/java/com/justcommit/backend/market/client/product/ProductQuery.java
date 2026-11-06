package com.justcommit.backend.market.client.product;

import java.util.List;

public interface ProductQuery {

    ProductResult getProduct(Long productId);

    // 없는 ID는 결과에서 빠진다
    List<ProductResult> getProducts(List<Long> productIds);
}
