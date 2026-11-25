package com.justcommit.backend.market;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OrderQuery {

    OrderResult getOrder(Long orderId);

    SellerOrderResult getSellerOrder(Long sellerOrderId);

    List<SellerOrderResult> getSellerOrderBySellerId(Long sellerId);

    // 배송이 택배 예약·알림·정산 이벤트에 쓰는 주문 정보 (판매자 주문 단위)
    OrderShippingInfo getShippingInfo(Long sellerOrderId);

    // 장바구니 추천용: since 이후 구매 확정된 상품 ID, 최신순 최대 limit개 (기준 시각은 판매자 주문의 confirmedAt)
    List<Long> getRecentConfirmedProductIds(Long buyerId, LocalDateTime since, int limit);

    // 리뷰 작성 판단용 판매자 주문 정보 (#582). 판매자 주문이 없으면 빈 값. 구매 확정 전이어도 값을 돌려준다
    Optional<SellerOrderReviewInfo> findSellerOrderReviewInfo(Long sellerOrderId);

    // 판매자 주문별 구매 확정(CONFIRMED)된 주문상품 이름, 주문상품 순서 (#583 리뷰 목록). 확정 상품이 없는 판매자 주문은 빠진다
    Map<Long, List<String>> getConfirmedProductNames(Collection<Long> sellerOrderIds);

    // 주문 id → 주문 시각(주문 생성 시각) (#584 내 리뷰 목록의 주문일). 없는 주문은 빠진다
    Map<Long, LocalDateTime> getOrderedAts(Collection<Long> orderIds);

}
