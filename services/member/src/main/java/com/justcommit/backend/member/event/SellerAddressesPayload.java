package com.justcommit.backend.member.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.justcommit.backend.member.address.domain.Address;

import java.util.List;

/**
 * 암호화 전 주소 데이터 형식 (MemberAddressChanged.encryptedAddresses의 원문, market과의 약속, kafka-topics.md).
 * - addresses는 바뀐 뒤 전체 목록이며 기본 배송지는 정확히 1개
 * - memberId · addressId는 숫자, isDefault는 참/거짓, 나머지는 문자열 (우편번호는 앞자리 0을 지키려고 문자열)
 * - address2는 없으면 칸을 두고 null (@JsonInclude(ALWAYS)로 null 포함을 명시, 실제 JSON은 테스트로 확인)
 * - 연락처는 정상 등록 경로에서 휴대폰 11자리로 검증하며, 여기서는 재검증하지 않는다
 * - 배송지 이름(addressName)은 넣지 않는다 (택배 예약에 필요 없고, 복제하는 개인정보를 줄임)
 */
public record SellerAddressesPayload(Long memberId, List<SellerAddress> addresses) {

  // 배송지 하나. addressId는 market이 수락 때 고른 주소(senderAddressId)로 쓴다
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record SellerAddress(Long addressId, boolean isDefault, String recipientName, String recipientPhone,
                              String zipcode, String address1, String address2) {

    public static SellerAddress from(Address address) {
      return new SellerAddress(address.getId(), address.isDefault(), address.getRecipientName(),
          address.getRecipientPhone(), address.getZipcode(), address.getAddress1(), address.getAddress2());
    }
  }
}
