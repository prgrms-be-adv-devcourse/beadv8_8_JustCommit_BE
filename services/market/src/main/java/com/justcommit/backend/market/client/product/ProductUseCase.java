package com.justcommit.backend.market.client.product;

import java.util.List;

public interface ProductUseCase {

    // 하나라도 예약할 수 없으면 아무것도 예약하지 않고 false
    boolean reserve(Long orderId, List<Long> productIds);

    boolean release(Long orderId);

    // false면 예약이 이미 풀렸거나 다른 주문에 팔린 상태라 호출한 쪽이 후속 처리
    boolean markSoldOut(Long orderId);

    boolean releaseItem(Long productId);
}
