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

  static final int MAX_ADDRESS_COUNT = 4; // 회원당 최대 배송지 개수 (기본 배송지 포함)

  private final AddressRepository addressRepository;
  private final MemberRepository memberRepository;

  // 목록
  public List<AddressResult> getAddresses(Long memberId) {
    return addressRepository.findAllByMemberIdOrderByIsDefaultDescIdDesc(memberId).stream()
        .map(AddressResult::from)
        .toList();
  }

  // 등록: 최대 4개, 기본으로 등록하면 기존 기본 배송지는 해제
  // 회원 행을 먼저 잠가 같은 회원의 동시 요청이 개수 · 기본 1개를 함께 넘지 않게 한다
  @Transactional
  public AddressResult addAddress(Long memberId, AddressCreateCommand command) {
    Member member = lockMember(memberId);
    if (addressRepository.countByMemberId(memberId) >= MAX_ADDRESS_COUNT) {
      throw new BusinessException(MemberErrorCode.ADDRESS_LIMIT_EXCEEDED);
    }

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

  // 기본 배송지 변경: 회원 잠금 → 기존 기본 해제 + 새 기본 설정을 한 트랜잭션
  @Transactional
  public AddressResult changeDefault(Long memberId, Long addressId) {
    lockMember(memberId);
    Address target = addressRepository.findByIdAndMemberId(addressId, memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.ADDRESS_NOT_FOUND)); // 남의 배송지도 404
    if (!target.isDefault()) {
      addressRepository.findByMemberIdAndIsDefaultTrue(memberId).ifPresent(Address::unmarkDefault);
      target.markDefault();
    }
    return AddressResult.from(target);
  }

  // 배송지를 바꾸는 경로는 회원 행을 먼저 잠근다 (순서를 통일해 교착 위험을 줄임).
  // 잠금 대기는 현재 DB 설정을 사용하고, 대기 · 실패 처리는 별도로 확인한다
  private Member lockMember(Long memberId) {
    return memberRepository.findByIdForUpdate(memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
  }
}
