package com.justcommit.backend.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 다른 모듈(market)에 공개하는 상품 상태 변경 API.
 * 구현체 - product 모듈 내부(application)
 */
public interface ProductUseCase {

    /**
     * 주문 생성 때 상품들을 이 주문으로 예약한다. (ACTIVE → PAYMENT_PENDING)
     * 전부 예약하거나 아무것도 예약하지 않는다. 같은 orderId로 다시 오면 처음 결과(성공)를 그대로 돌려준다.
     *
     * @param expectedPrices 상품 ID → 주문서에 보여 준 가격
     * @throws com.justcommit.backend.common.exception.BusinessException
     *         판매 중이 아니거나 숨겨진 상품이 있으면 PRODUCT_NOT_RESERVABLE,
     *         가격이 하나라도 다르면 PRICE_CHANGED, 이미 해제된 orderId면 RESERVATION_RELEASED (모두 409)
     */
    void reserve(Long orderId, Map<Long, BigDecimal> expectedPrices);

    /**
     * 결제 전 만료 · 결제 확정 실패 때 해당 주문이 예약한 상품을 모두 해제한다. (PAYMENT_PENDING → ACTIVE)
     * 예약 기록을 RELEASED로 남겨, 이 orderId로 늦게 도착한 예약은 거절된다.
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
    /**
     * 환불 성공 뒤(취소 · 반품) 이 주문이 잡은 상품 한 건을 다시 판매 중으로 되돌린다.
     * (PAYMENT_PENDING 또는 SOLD_OUT → ACTIVE, reservedOrderId 비움)
     * 조건이 orderId라, 같은 이벤트가 두 번 오거나 이미 다른 주문에 다시 팔린 상품이면 아무것도 바꾸지 않는다.
     *
     * @param hidden true면 숨김도 켠다 (판매자 거절 · 자동 거절 · 반품). false면 숨김 상태를 그대로 둔다
     * @return 되돌렸으면 true, 바꿀 상품이 없으면 false (정상)
     */
    boolean releaseItem(Long productId, Long orderId, boolean hidden);

}