package com.justcommit.backend.market.shipment.application;

import com.justcommit.backend.market.shipment.domain.ShippingRegion;

import java.math.BigDecimal;

// 배송지 우편번호 하나의 배송비 계산 결과. fee = baseFee + extraFee (지역 할증 하나)
public record ShippingFeeQuote(ShippingRegion region, BigDecimal baseFee, BigDecimal extraFee) {

    public BigDecimal fee() {
        return baseFee.add(extraFee);
    }
}
