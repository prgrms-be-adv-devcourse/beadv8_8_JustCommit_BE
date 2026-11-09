package com.justcommit.backend.market.client.product;

import com.justcommit.backend.common.client.InternalApiHeaders;
import com.justcommit.backend.common.response.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

@HttpExchange("/internal/v1/products")
public interface ProductApi {

    @GetExchange
    ApiResponse<List<ProductResult>> getProducts(@RequestParam List<Long> ids);

    @PostExchange("/reservations")
    void reserve(@RequestHeader(InternalApiHeaders.IDEMPOTENCY_KEY) Long orderId,
                 @RequestBody ReservationRequest request);

    record ReservationRequest(Long orderId, List<ReservationItem> items) {
    }
}
