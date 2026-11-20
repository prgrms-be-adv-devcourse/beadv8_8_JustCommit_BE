package com.justcommit.backend.market.shipment.domain;

// 요금표의 우편번호 구간 하나. 양끝 포함, 5자리 문자열 비교 (404-express docs/design.md 「배송비 요금표」)
public record ZipcodeRange(String from, String to, String name) {

    public boolean contains(String zipcode) {
        return zipcode.compareTo(from) >= 0 && zipcode.compareTo(to) <= 0;
    }
}
