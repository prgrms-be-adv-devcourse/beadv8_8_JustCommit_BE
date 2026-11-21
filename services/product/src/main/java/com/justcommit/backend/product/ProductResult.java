package com.justcommit.backend.product;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 다른 모듈(market 등)에 공개하는 상품 조회 결과.
 *
 * @param productId    상품 ID
 * @param sellerId     판매자 회원 ID (SellerOrder를 판매자별로 묶을 때 사용)
 * @param title        상품 제목 (ORDERS_ITEM.product_name으로 복사)
 * @param price        가격 (CART_ITEM.price, ORDERS_ITEM.price로 복사)
 * @param status       판매 상태
 * @param hidden       구매자에게 숨겨졌는지 (판매자 숨김 또는 관리자 강제 숨김)
 * @param thumbnailUrl 대표 사진 URL (없으면 null)
 * @param speciesName  품종 이름 (장바구니 등 화면 표시용)
 * @param createdAt    상품 등록 시각 (장바구니 추천 카드의 「3일 전」 표시용)
 */
public record ProductResult(
        Long productId,
        Long sellerId,
        String title,
        BigDecimal price,
        ProductStatus status,
        boolean hidden,
        String thumbnailUrl,
        String speciesName,
        LocalDateTime createdAt
) {

    /**
     * 지금 장바구니에 담거나 주문할 수 있는 상품인지 여부.
     * 판매 가능 규칙을 product 모듈이 한곳에서 관리하기 위해 제공한다.
     */
    public boolean isPurchasable() {
        return status == ProductStatus.ACTIVE && !hidden;
    }
}
