package com.justcommit.backend.product.product.application;

import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ReviewScope;

// 상품 등록 · 수정에서 사진 검수를 요청하는 창구 (구현: review.application.ImageReviewService)
// product가 review 구현을 직접 알지 않게 product 쪽에 둠 → product ↔ review 순환 의존 제거
public interface ReviewRequester {

    // 등록 · 수정 트랜잭션 안에서만 부름 (상품 저장과 검수 요청이 함께 커밋 · 롤백)
    void request(Product product, ReviewScope scope);
}
