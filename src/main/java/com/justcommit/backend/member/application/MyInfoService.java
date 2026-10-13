package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 마이페이지 내 정보 조회 (기본 정보 + 계좌 + 기본 배송지)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyInfoService {

  private static final String MASK = "****";
  private static final int VISIBLE_DIGITS = 4;

  private final MemberRepository memberRepository;
  private final AddressRepository addressRepository;
  private final AccountCipher accountCipher;

  public MyInfoResult getMyInfo(Long memberId) {
    Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

    // 복호화한 원문은 이 메서드 안에서만 쓰고 밖으로는 마스킹한 값만 내보냄
    MyInfoResult.Account account = new MyInfoResult.Account(
            member.getBankCode(),
            mask(accountCipher.decrypt(member.getAccountNoEnc())),
            member.getAccountHolder()
    );

    // 기본 배송지가 없어도 마이페이지는 열려야 하므로 404 대신 null
    MyInfoResult.DefaultAddress defaultAddress = addressRepository.findByMemberIdAndIsDefaultTrue(memberId)
            .map(MyInfoService::toDefaultAddress)
            .orElse(null);

    return new MyInfoResult(
            member.getId(),
            member.getEmail(),
            member.getNickname(),
            member.getPhone(),
            member.getRole().name(),
            member.getProvider().name(),
            account,
            defaultAddress
    );
  }

  // 뒤 4자리만 남기고 가림
  static String mask(String accountNo) {
    return MASK + accountNo.substring(accountNo.length() - VISIBLE_DIGITS);
  }

  private static MyInfoResult.DefaultAddress toDefaultAddress(Address address) {
    return new MyInfoResult.DefaultAddress(
            address.getId(),
            address.getAddressName(),
            address.getRecipientName(),
            address.getRecipientPhone(),
            address.getZipcode(),
            address.getAddress1(),
            address.getAddress2()
    );
  }
}
