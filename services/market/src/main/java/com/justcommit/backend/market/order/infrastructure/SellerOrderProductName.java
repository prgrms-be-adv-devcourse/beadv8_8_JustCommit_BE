package com.justcommit.backend.market.order.infrastructure;

// 판매자 주문 id와 주문상품 이름 한 쌍 (리뷰 목록의 구매 상품명 조회용, #583)
public record SellerOrderProductName(Long sellerOrderId, String productName) {
}
