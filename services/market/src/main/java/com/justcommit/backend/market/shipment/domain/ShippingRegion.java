package com.justcommit.backend.market.shipment.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 배송지 우편번호가 요금표의 어느 할증 구간에 드는지. 할증은 하나만 붙는다 (도서산간 → 제주 순으로 본다)
@Getter
@RequiredArgsConstructor
public enum ShippingRegion {
    GENERAL("일반"),
    JEJU("제주"),
    REMOTE("도서산간");

    private final String label;
}
