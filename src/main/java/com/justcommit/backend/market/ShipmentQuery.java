package com.justcommit.backend.market;

import java.util.List;
import java.util.Map;

public interface ShipmentQuery {

    ShipmentResult findBySellerOrderId(Long sellerOrderId);
    Map<Long, ShipmentResult> findBySellerOrderIds(List<Long> sellerOrderIds);
}
