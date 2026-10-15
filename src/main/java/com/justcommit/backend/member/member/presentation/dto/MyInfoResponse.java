package com.justcommit.backend.member.member.presentation.dto;

import com.justcommit.backend.member.member.application.MyInfoResult;

import java.time.LocalDateTime;

public record MyInfoResponse(
        Long memberId,
        String email,
        String nickname,
        String phone,
        String role,
        String provider,
        LocalDateTime createdAt,
        AccountResponse account,
        DefaultAddressResponse defaultAddress
) {

  public static MyInfoResponse from(MyInfoResult result) {
    return new MyInfoResponse(
            result.memberId(),
            result.email(),
            result.nickname(),
            result.phone(),
            result.role(),
            result.provider(),
            result.createdAt(),
            AccountResponse.from(result.account()),
            DefaultAddressResponse.from(result.defaultAddress())
    );
  }

  public record AccountResponse(
          String bankCode,
          String maskedAccountNo,
          String accountHolder
  ) {
    static AccountResponse from(MyInfoResult.Account account) {
      return new AccountResponse(
              account.bankCode(),
              account.maskedAccountNo(),
              account.accountHolder()
      );
    }
  }

  public record DefaultAddressResponse(
          Long addressId,
          String addressName,
          String recipientName,
          String recipientPhone,
          String zipcode,
          String address1,
          String address2
  ) {
    static DefaultAddressResponse from(MyInfoResult.DefaultAddress address) {
      if (address == null) {
        return null;
      }
      return new DefaultAddressResponse(
              address.addressId(),
              address.addressName(),
              address.recipientName(),
              address.recipientPhone(),
              address.zipcode(),
              address.address1(),
              address.address2()
      );
    }
  }
}
