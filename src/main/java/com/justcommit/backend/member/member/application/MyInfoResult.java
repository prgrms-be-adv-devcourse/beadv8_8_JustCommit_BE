package com.justcommit.backend.member.member.application;

import java.time.LocalDateTime;

// 정보 조회 결과(계좌번호는 마스킹된 값)
public record MyInfoResult(
        Long memberId,
        String email,
        String nickname,
        String phone,
        String role,
        String provider,
        LocalDateTime createdAt,
        Account account,
        DefaultAddress defaultAddress
) {

  public record Account(
          String bankCode,
          String maskedAccountNo,
          String accountHolder
  ) {
  }

  public record DefaultAddress(
          Long addressId,
          String addressName,
          String recipientName,
          String recipientPhone,
          String zipcode,
          String address1,
          String address2
  ) {
  }
}
