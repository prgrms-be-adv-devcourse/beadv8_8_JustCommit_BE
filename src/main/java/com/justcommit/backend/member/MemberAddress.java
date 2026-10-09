package com.justcommit.backend.member;

// 회원 배송지 정보(주문 모듈이 배송 정보 스냅샷으로 복사)
public record MemberAddress(
        Long addressId,
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2 // 상세 주소, NULL 가능
) {
}
