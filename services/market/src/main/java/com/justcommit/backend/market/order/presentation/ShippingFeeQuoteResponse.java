package com.justcommit.backend.market.order.presentation;

import com.justcommit.backend.market.shipment.application.ShippingFeeQuote;
import com.justcommit.backend.market.shipment.domain.ShippingRegion;

import java.math.BigDecimal;
import java.util.List;

// 배송지 우편번호 하나의 판매자별 배송비. 배송비는 우편번호로만 정해져 판매자마다 같고, 주문 생성 때 판매자 주문에 저장되는 값과 같다
public record ShippingFeeQuoteResponse(
        ShippingRegion region,
        String regionLabel,
        List<SellerFee> sellers
) {

    public record SellerFee(Long sellerId, BigDecimal fee, BigDecimal baseFee, BigDecimal extraFee) {
    }

    public static ShippingFeeQuoteResponse of(ShippingFeeQuote quote, List<Long> sellerIds) {
        List<SellerFee> sellers = sellerIds.stream()
                .distinct()
                .map(sellerId -> new SellerFee(sellerId, quote.fee(), quote.baseFee(), quote.extraFee()))
                .toList();
        return new ShippingFeeQuoteResponse(quote.region(), quote.region().getLabel(), sellers);
    }
}
