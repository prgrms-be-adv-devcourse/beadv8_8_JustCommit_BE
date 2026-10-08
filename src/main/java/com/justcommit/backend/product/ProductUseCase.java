package com.justcommit.backend.product;

import java.util.List;

/**
 * 다른 모듈(market)에 공개하는 상품 상태 변경 API.
 * 구현체 - product 모듈 내부(application)
 */
public interface ProductUseCase {

    /**
     * 주문서 작성 시 상품들을 해당 주문으로 예약한다. (ACTIVE → PAYMENT_PENDING)
     * 하나라도 예약할 수 없으면 아무것도 예약하지 않는다.
     *
     * @return 모두 예약되면 true, 판매 중이 아니거나 숨겨진 상품이 하나라도 있으면 false
     *         (false인 경우 호출하는 쪽에서 주문 생성을 중단하거나 취소해야 함)
     */
    boolean reserve(Long orderId, List<Long> productIds);

    /**
     * 결제 실패, 주문 만료, 주문 취소 시 해당 주문이 예약한 상품을 모두 해제한다. (PAYMENT_PENDING → ACTIVE)
     * (주문 만료와 취소가 겹치는 등 두 번 호출되어도 안전하게 하기 위함)
     *
     * @return 해제한 상품이 있으면 true, 이 주문으로 예약된 상품이 없으면 false
     *         (이미 해제된 경우에도 false이며, 정상적인 결과임)
     */
    boolean release(Long orderId);

    /**
     * 결제 완료 시 해당 주문이 예약한 상품을 모두 판매 완료로 바꾼다. (PAYMENT_PENDING → SOLD_OUT)
     *
     * @return 판매 완료 처리되면 true, 이 주문으로 예약된 상품이 없으면 false
     *         (false인 경우 예약이 이미 해제되었거나 다른 주문에 판매된 상황이므로,
     *         호출하는 쪽에서 결제 취소 등 후속 처리가 필요함)
     */
    boolean markSoldOut(Long orderId);
}