package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberAccount;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.member.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// MemberQuery 구현 (다른 모듈은 이 클래스를 모르고 MemberQuery 인터페이스만 사용)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberQueryService implements MemberQuery {

  private final MemberRepository memberRepository;
  private final AddressRepository addressRepository;
  private final AccountCipher accountCipher;

  @Override
  public MemberAccount getAccount(Long memberId) {
    Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

    return new MemberAccount(
            member.getBankCode(),
            accountCipher.decrypt(member.getAccountNoEnc()), // 필요한 순간에만 복호화
            member.getAccountHolder()
    );
  }

  @Override
  public MemberAddress getAddress(Long memberId, Long addressId) {
    // memberId 조건을 같이 걸어 다른 회원의 배송지는 "없음"과 똑같이 404
    Address address = addressRepository.findByIdAndMemberId(addressId, memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.ADDRESS_NOT_FOUND));
    return toMemberAddress(address);
  }

  @Override
  public MemberAddress getDefaultAddress(Long memberId) {
    Address address = addressRepository.findByMemberIdAndIsDefaultTrue(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.ADDRESS_NOT_FOUND));
    return toMemberAddress(address);
  }

  // 엔티티 → 공개 DTO (다른 모듈에 Address 엔티티를 노출하지 않음)
  private MemberAddress toMemberAddress(Address address) {
    return new MemberAddress(
            address.getId(),
            address.getRecipientName(),
            address.getRecipientPhone(),
            address.getZipcode(),
            address.getAddress1(),
            address.getAddress2()
    );
  }
}
