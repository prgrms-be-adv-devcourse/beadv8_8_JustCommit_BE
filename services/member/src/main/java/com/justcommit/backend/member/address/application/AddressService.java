package com.justcommit.backend.member.address.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
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
  private final SellerRepository sellerRepository;
  private final SellerAddressEventFactory sellerAddressEventFactory;
  private final OutboxPublisher outboxPublisher;

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
    publishSellerAddressesIfSeller(memberId);
    return AddressResult.from(address);
  }

  // 기본 배송지 변경: 회원 잠금 → 대상 조회 → 이미 기본이면 종료 → 기존 기본 해제 · 대상 기본 설정 → 판매자면 발행
  // 판매자가 아니어도 기본 변경은 항상 한다 (발행만 판매자일 때)
  @Transactional
  public AddressResult changeDefault(Long memberId, Long addressId) {
    lockMember(memberId);
    Address target = addressRepository.findByIdAndMemberId(addressId, memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.ADDRESS_NOT_FOUND)); // 남의 배송지도 404

    if (target.isDefault()) {
      return AddressResult.from(target); // 바뀐 게 없으므로 발행하지 않음
    }
    addressRepository.findByMemberIdAndIsDefaultTrue(memberId).ifPresent(Address::unmarkDefault);
    target.markDefault();

    publishSellerAddressesIfSeller(memberId);
    return AddressResult.from(target);
  }

  // 판매자면 바뀐 뒤 배송지 목록을 market에 보낸다 (회원 잠금을 얻은 같은 트랜잭션 안에서)
  // 판매자 여부는 판매자 행으로 판단 (Seller PK = 회원 id, ADMIN 판매자 포함)
  // 배송지 수정 · 삭제가 생기면 같은 회원 잠금 뒤 이 메서드를 불러야 한다
  private void publishSellerAddressesIfSeller(Long memberId) {
    if (sellerRepository.existsById(memberId)) {
      outboxPublisher.publish(sellerAddressEventFactory.create(memberId));
    }
  }

  // 배송지를 바꾸는 경로는 회원 행을 먼저 잠근다 (순서를 통일해 교착 위험을 줄임).
  // 잠금 대기는 현재 DB 설정을 사용하고, 대기 · 실패 처리는 별도로 확인한다
  private Member lockMember(Long memberId) {
    return memberRepository.findByIdForUpdate(memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
  }
}
