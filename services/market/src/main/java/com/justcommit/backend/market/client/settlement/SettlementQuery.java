package com.justcommit.backend.market.client.settlement;

import java.time.LocalDateTime;
import java.util.Optional;

public interface SettlementQuery {

    Optional<LocalDateTime> findSettledAtBySellerOrderId(Long sellerOrderId);
}
