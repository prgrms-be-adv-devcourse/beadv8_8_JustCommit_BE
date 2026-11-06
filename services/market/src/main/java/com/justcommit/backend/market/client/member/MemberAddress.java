package com.justcommit.backend.market.client.member;

public record MemberAddress(
        Long addressId,
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2 // 상세 주소, NULL 가능
) {
}
