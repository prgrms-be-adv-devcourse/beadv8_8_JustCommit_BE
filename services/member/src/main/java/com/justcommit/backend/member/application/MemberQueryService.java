package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberAccount;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.MemberQuery;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

// MemberQuery 구현 (다른 모듈은 이 클래스를 모르고 MemberQuery 인터페이스만 사용)
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberQueryService implements MemberQuery {

  private final MemberRepository memberRepository;
  private final AddressRepository addressRepository;
  private final SellerRepository sellerRepository;
  private final AccountCipher accountCipher;

  // 없는 회원 · 탈퇴 회원은 같은 404 (탈퇴 회원의 계좌는 내주지 않음) INACTIVE는 의미가 정해지지 않아 기존처럼 응답
  @Override
  public MemberAccount getAccount(Long memberId) {
    Member member = memberRepository.findById(memberId)
            .filter(m -> m.getStatus() != MemberStatus.DELETED)
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

  // 판매자 테이블 PK = 회원 ID(@MapsId)라서 existsById로 바로 확인
  @Override
  public boolean isSeller(Long memberId) {
    return sellerRepository.existsById(memberId);
  }

  // 상품 상세용 판매자 닉네임
  @Override
  public String getNickname(Long memberId) {
    return memberRepository.findById(memberId)
            .map(Member::getNickname)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
  }

  // 장바구니용 판매자 닉제인 일괄 조회(in 조회 한 번)
  @Override
  public Map<Long, String> getNicknames(Collection<Long> memberIds) {
    if (memberIds == null || memberIds.isEmpty()) {
      return Map.of();
    }

    List<Long> ids = memberIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();

    return memberRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(Member::getId, Member::getNickname));
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
