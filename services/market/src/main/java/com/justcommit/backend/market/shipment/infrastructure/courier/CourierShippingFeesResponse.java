package com.justcommit.backend.market.shipment.infrastructure.courier;

import com.justcommit.backend.market.shipment.domain.ShippingFeeTable;
import com.justcommit.backend.market.shipment.domain.ZipcodeRange;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

// 택배사 GET /api/v1/shippingFees 응답의 data (404-express docs/design.md 「배송비 요금표」)
public record CourierShippingFeesResponse(
        Integer version,
        OffsetDateTime updatedAt,
        BigDecimal baseFee,
        BigDecimal jejuSurcharge,
        BigDecimal remoteSurcharge,
        List<ZipcodeRange> jejuZipcodes,
        List<ZipcodeRange> remoteZipcodes
) {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public ShippingFeeTable toDomain() {
        return new ShippingFeeTable(version, updatedAt.atZoneSameInstant(SEOUL).toLocalDateTime(),
                baseFee, jejuSurcharge, remoteSurcharge, jejuZipcodes, remoteZipcodes);
    }
}
