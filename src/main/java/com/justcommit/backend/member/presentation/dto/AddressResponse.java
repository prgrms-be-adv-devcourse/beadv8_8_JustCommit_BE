package com.justcommit.backend.member.presentation.dto;

import com.justcommit.backend.member.application.AddressResult;

// 배송지 응답 (목록·등록·기본 변경 공통)
public record AddressResponse(
        Long addressId,
        String addressName,
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2,
        boolean isDefault
) {
  public static AddressResponse from(AddressResult result) {
    return new AddressResponse(
            result.addressId(),
            result.addressName(),
            result.recipientName(),
            result.recipientPhone(),
            result.zipcode(),
            result.address1(),
            result.address2(),
            result.isDefault()
    );
  }
}
