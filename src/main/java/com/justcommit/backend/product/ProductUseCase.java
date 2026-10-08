package com.justcommit.backend.product;

import java.util.List;

/**
 * 다른 모듈(market)에 공개하는 상품 상태 변경 API.
 * 구현체는 product 모듈 내부(application)에 있다.
 */
public interface ProductUseCase {

    /**
     * 주문서 작성 시 상품들을 해당 주문으로 예약한다. (ACTIVE → PAYMENT_PENDING)
     * 하나라도 예약할 수 없으면 전부 실패한다.
     *
     * @throws com.justcommit.backend.common.exception.BusinessException
     *         상품이 없으면 PRODUCT_NOT_FOUND, 판매 중이 아니면 PRODUCT_NOT_PURCHASABLE
     */
    void reserve(Long orderId, List<Long> productIds);

    /**
     * 결제 실패, 주문 만료, 주문 취소 시 해당 주문이 예약한 상품을 모두 해제한다. (PAYMENT_PENDING → ACTIVE)
     * 예약된 상품이 없으면 아무것도 하지 않는다.
     * (주문 만료와 취소가 겹치는 등 두 번 호출되어도 안전하게 하기 위함)
     */
    void release(Long orderId);

    /**
     * 결제 완료 시 해당 주문이 예약한 상품을 모두 판매 완료로 바꾼다. (PAYMENT_PENDING → SOLD_OUT)
     *
     * @throws com.justcommit.backend.common.exception.BusinessException
     *         해당 주문으로 예약된 상품이 없으면 RESERVATION_NOT_FOUND
     *         (예약이 이미 해제되었거나 다른 주문에 판매된 경우로, 결제 취소 등 후속 처리가 필요함)
     */
    void markSoldOut(Long orderId);
}