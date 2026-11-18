package com.justcommit.backend.member.address.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.lock.MemberLocker;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// 배송지 목록·등록·기본 배송지 변경·수정·삭제
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AddressService {

  static final int MAX_ADDRESS_COUNT = 4; // 회원당 최대 배송지 개수 (기본 배송지 포함)

  private final AddressRepository addressRepository;
  private final MemberLocker memberLocker;
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

  // 배송지 부분 수정 (#416): 회원 잠금 → 대상 조회 → 보낸 항목만 변경 → 실제로 바뀌었고 판매자면 발행
  // 같은 값만 보냈으면 바뀐 게 없으므로 발행하지 않음
  @Transactional
  public AddressResult updateAddress(Long memberId, Long addressId, AddressUpdateCommand command) {
    lockMember(memberId);
    Address target = addressRepository.findByIdAndMemberId(addressId, memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.ADDRESS_NOT_FOUND)); // 남의 배송지도 404

    // 주소가 실제로 바뀌는데 상세 주소가 없으면 옛 상세 주소가 새 주소에 남으므로 거절 (비우려면 "")
    if (target.changesLocation(command.zipcode(), command.address1()) && command.address2() == null) {
      throw new BusinessException(MemberErrorCode.ADDRESS_DETAIL_REQUIRED);
    }

    boolean changed = target.update(command.recipientName(), command.recipientPhone(), command.zipcode(),
        command.address1(), command.address2(), command.addressName());
    if (changed) {
      publishSellerAddressesIfSeller(memberId);
    }
    return AddressResult.from(target);
  }

  // 배송지 삭제 (#416): 회원 잠금 → 대상 조회 → 기본 배송지면 409 → 삭제 → 판매자면 발행
  // 기본 배송지는 항상 1개라 지울 수 없다 (배송지가 하나뿐이면 그것이 기본이라 같은 409)
  @Transactional
  public void deleteAddress(Long memberId, Long addressId) {
    lockMember(memberId);
    Address target = addressRepository.findByIdAndMemberId(addressId, memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.ADDRESS_NOT_FOUND)); // 남의 배송지도 404

    if (target.isDefault()) {
      throw new BusinessException(MemberErrorCode.DEFAULT_ADDRESS_NOT_DELETABLE);
    }
    addressRepository.delete(target);
    // 목록을 조회하기 직전에 JPA가 삭제를 먼저 반영하므로, 발행되는 목록에는 지운 배송지가 없다
    publishSellerAddressesIfSeller(memberId);
  }

  // 판매자면 바뀐 뒤 배송지 목록을 market에 보낸다 (회원 잠금을 얻은 같은 트랜잭션 안에서)
  // 판매자 여부는 판매자 행으로 판단 (Seller PK = 회원 id, ADMIN 판매자 포함)
  // 추가 · 기본 변경 · 수정 · 삭제 모두 같은 회원 잠금 뒤 이 메서드를 부른다
  private void publishSellerAddressesIfSeller(Long memberId) {
    if (sellerRepository.existsById(memberId)) {
      outboxPublisher.publish(sellerAddressEventFactory.create(memberId));
    }
  }

  // 배송지를 바꾸는 경로는 회원 행을 먼저 잠근다 (순서를 통일해 교착 위험을 줄임).
  // 대기 제한을 넘으면 MemberLocker가 409 MEMBER_BUSY로 바꾸고 트랜잭션은 롤백된다
  private Member lockMember(Long memberId) {
    return memberLocker.lockById(memberId)
        .filter(m -> m.getStatus() != MemberStatus.DELETED) // 탈퇴 회원에게 배송지를 다시 저장하지 않음 (#407)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
  }
}
