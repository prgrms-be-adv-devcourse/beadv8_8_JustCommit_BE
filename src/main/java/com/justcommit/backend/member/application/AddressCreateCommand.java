package com.justcommit.backend.member.application;

// 배송지 등록 입력값 (컨트롤러 -> 서비스)
public record AddressCreateCommand(
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2,
        String addressName,
        boolean isDefault // true면 기본 배송지로 등록
) {
}
