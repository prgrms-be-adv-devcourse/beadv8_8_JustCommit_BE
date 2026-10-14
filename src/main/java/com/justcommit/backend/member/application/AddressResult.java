package com.justcommit.backend.member.application;

import com.justcommit.backend.member.domain.Address;

// 배송지 조회·등록 결과 (서비스 → 컨트롤러)
public record AddressResult(
        Long addressId,
        String addressName,
        String recipientName,
        String recipientPhone,
        String zipcode,
        String address1,
        String address2,
        boolean isDefault
) {

  // 엔티티 → 결과 변환
  public static AddressResult from(Address address) {
    return new AddressResult(
            address.getId(),
            address.getAddressName(),
            address.getRecipientName(),
            address.getRecipientPhone(),
            address.getZipcode(),
            address.getAddress1(),
            address.getAddress2(),
            address.isDefault()
    );
  }
}
