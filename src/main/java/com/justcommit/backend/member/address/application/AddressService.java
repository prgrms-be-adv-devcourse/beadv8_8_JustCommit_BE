package com.justcommit.backend.member.address.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// 배송지 목록·등록·기본 배송지 변경
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AddressService {

  static final int MAX_ADDRESS_COUNT = 10; // 회원당 최대 배송지 개수

  private final AddressRepository addressRepository;
  private final MemberRepository memberRepository;

  // 목록
  public List<AddressResult> getAddresses(Long memberId) {
    return addressRepository.findAllByMemberIdOrderByIsDefaultDescIdDesc(memberId).stream()
            .map(AddressResult::from)
            .toList();
  }

  // 등록: 최대 10개, 기본으로 등록하면 기존 기본배송지는 해제
  @Transactional
  public AddressResult addAddress(Long memberId, AddressCreateCommand command) {
    if (addressRepository.countByMemberId(memberId) >= MAX_ADDRESS_COUNT) {
      throw new BusinessException(MemberErrorCode.ADDRESS_LIMIT_EXCEEDED);
    }
    Member member = memberRepository.findById(memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));

    // 기본 배송지가 하나도 없으면 체크 안 해도 기본으로 등록(기본 배송지가 비지 않게)
    Optional<Address> currentDefault = addressRepository.findByMemberIdAndIsDefaultTrue(memberId);
    boolean makeDefault = command.isDefault() || currentDefault.isEmpty();
    if (makeDefault) {
      currentDefault.ifPresent(Address::unmarkDefault);
    }

    Address address = addressRepository.save(Address.create(member,
            command.recipientName(), command.recipientPhone(), command.zipcode(),
            command.address1(), command.address2(), command.addressName(), makeDefault));
    return AddressResult.from(address);
  }

  // 기본 배송지 변경: 기존 기본 해제 + 새 기본 설정을 한 트랜잭션
  @Transactional
  public AddressResult changeDefault(Long memberId, Long addressId) {
    Address target = addressRepository.findByIdAndMemberId(addressId, memberId)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.ADDRESS_NOT_FOUND)); // 남의 배송지도 404

    if (!target.isDefault()) {
      addressRepository.findByMemberIdAndIsDefaultTrue(memberId).ifPresent(Address::unmarkDefault);
      target.markDefault();
    }
    return AddressResult.from(target);
  }
}
