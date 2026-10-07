package com.justcommit.backend.market;

import java.util.List;

public interface OrderQuery {

    OrderResult getOrder(Long orderId);

    SellerOrderResult getSellerOrder(Long sellerOrderId);

    List<SellerOrderResult> getSellerOrderBySellerId(Long sellerId);

}
