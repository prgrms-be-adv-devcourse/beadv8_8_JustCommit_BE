package com.justcommit.backend.member.address.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.event.MemberAddressChanged;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

  private static final Long MEMBER_ID = 1L;

  @Mock
  AddressRepository addressRepository;

  @Mock
  MemberRepository memberRepository;

  @Mock
  SellerRepository sellerRepository;

  @Mock
  SellerAddressEventFactory sellerAddressEventFactory;

  @Mock
  OutboxPublisher outboxPublisher;

  @InjectMocks
  AddressService addressService;

  // 테스트용 배송지(id를 직접 넣어 줌)
  private Address address(Long id, boolean isDefault) {
    Address address = Address.create(mock(Member.class), "홍길동", "01012345678",
            "06236", "서울 강남구 테헤란로 1", null, null, isDefault);
    ReflectionTestUtils.setField(address, "id", id);
    return address;
  }

  private AddressCreateCommand command(boolean isDefault) {
    return new AddressCreateCommand("홍길동", "01012345678", "06236",
            "서울 강남구 테헤란로 1", "5층", "회사", isDefault);
  }

  // save가 넘겨받은 배송지를 그대로 돌려주게 함
  private void givenSaveReturnsArgument() {
    given(addressRepository.save(any(Address.class))).willAnswer(inv -> inv.getArgument(0));
  }

  @Test
  @DisplayName("배송지 목록을 저장소 정렬 순서 그대로 반환")
  void getAddresses() {
    given(addressRepository.findAllByMemberIdOrderByIsDefaultDescIdDesc(MEMBER_ID))
            .willReturn(List.of(address(1L, true), address(3L, false), address(2L, false)));

    List<AddressResult> results = addressService.getAddresses(MEMBER_ID);

    assertThat(results).extracting(AddressResult::addressId).containsExactly(1L, 3L, 2L);
    assertThat(results.get(0).isDefault()).isTrue();
  }

  @Test
  @DisplayName("기본 배송지로 등록하면 기존 기본 배송지는 해제")
  void addAddress_asDefault() {
    Address oldDefault = address(1L, true);
    given(addressRepository.countByMemberId(MEMBER_ID)).willReturn(1L);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false); // 판매자 아님 → 발행 안 함
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(oldDefault));
    givenSaveReturnsArgument();

    AddressResult result = addressService.addAddress(MEMBER_ID, command(true));

    assertThat(result.isDefault()).isTrue();
    assertThat(oldDefault.isDefault()).isFalse();
  }

  @Test
  @DisplayName("기본 배송지로 선택하지 않으면 기존 기본 배송지는 그대로")
  void addAddress_notDefault() {
    Address oldDefault = address(1L, true);
    given(addressRepository.countByMemberId(MEMBER_ID)).willReturn(1L);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false); // 판매자 아님 → 발행 안 함
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(oldDefault));
    givenSaveReturnsArgument();

    AddressResult result = addressService.addAddress(MEMBER_ID, command(false));

    assertThat(result.isDefault()).isFalse();
    assertThat(oldDefault.isDefault()).isTrue();
  }

  @Test
  @DisplayName("기본 배송지가 없으면 선택하지 않아도 기본 배송지로 등록")
  void addAddress_noDefaultYet() {
    given(addressRepository.countByMemberId(MEMBER_ID)).willReturn(0L);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false); // 판매자 아님 → 발행 안 함
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.empty());
    givenSaveReturnsArgument();

    AddressResult result = addressService.addAddress(MEMBER_ID, command(false));

    assertThat(result.isDefault()).isTrue();
  }

  @Test
  @DisplayName("배송지가 이미 4개면 등록할 수 없음")
  void addAddress_limitExceeded() {
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.countByMemberId(MEMBER_ID)).willReturn(4L);

    assertThatThrownBy(() -> addressService.addAddress(MEMBER_ID, command(false)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ADDRESS_LIMIT_EXCEEDED);
    then(addressRepository).should(never()).save(any());
  }

  @Test
  @DisplayName("기본 배송지를 변경하면 기존 기본은 해제되고 대상이 기본 배송지가 됨")
  void changeDefault() {
    Address oldDefault = address(1L, true);
    Address target = address(2L, false);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.findByIdAndMemberId(2L, MEMBER_ID)).willReturn(Optional.of(target));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false); // 판매자 아님 → 발행 안 함
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(oldDefault));

    AddressResult result = addressService.changeDefault(MEMBER_ID, 2L);

    assertThat(result.addressId()).isEqualTo(2L);
    assertThat(target.isDefault()).isTrue();
    assertThat(oldDefault.isDefault()).isFalse();
  }

  @Test
  @DisplayName("내 배송지가 아니면 404 ADDRESS_NOT_FOUND")
  void changeDefault_notFound() {
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.findByIdAndMemberId(99L, MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> addressService.changeDefault(MEMBER_ID, 99L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ADDRESS_NOT_FOUND);
  }

  @Test
  @DisplayName("판매자가 배송지를 추가하면 바뀐 뒤 배송지 목록 이벤트를 발행")
  void addAddress_seller_publishes() {
    MemberAddressChanged event = new MemberAddressChanged(MEMBER_ID, "v1.k1:test");
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.countByMemberId(MEMBER_ID)).willReturn(1L);
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(address(1L, true)));
    givenSaveReturnsArgument();
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(true);
    given(sellerAddressEventFactory.create(MEMBER_ID)).willReturn(event);

    addressService.addAddress(MEMBER_ID, command(false));

    then(outboxPublisher).should().publish(event);
  }

  @Test
  @DisplayName("판매자가 아니면 배송지를 추가해도 발행하지 않음")
  void addAddress_notSeller_noPublish() {
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.countByMemberId(MEMBER_ID)).willReturn(1L);
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(address(1L, true)));
    givenSaveReturnsArgument();
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);

    addressService.addAddress(MEMBER_ID, command(false));

    then(sellerAddressEventFactory).shouldHaveNoInteractions();
    then(outboxPublisher).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("판매자가 기본 배송지를 바꾸면 기본이 바뀌고 발행")
  void changeDefault_seller_publishes() {
    MemberAddressChanged event = new MemberAddressChanged(MEMBER_ID, "v1.k1:test");
    Address oldDefault = address(1L, true);
    Address target = address(2L, false);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.findByIdAndMemberId(2L, MEMBER_ID)).willReturn(Optional.of(target));
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(oldDefault));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(true);
    given(sellerAddressEventFactory.create(MEMBER_ID)).willReturn(event);

    addressService.changeDefault(MEMBER_ID, 2L);

    assertThat(target.isDefault()).isTrue();
    assertThat(oldDefault.isDefault()).isFalse();
    then(outboxPublisher).should().publish(event);
  }

  @Test
  @DisplayName("판매자가 아니어도 기본 배송지는 바뀌고, 발행만 하지 않음")
  void changeDefault_notSeller_changesWithoutPublish() {
    Address oldDefault = address(1L, true);
    Address target = address(2L, false);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.findByIdAndMemberId(2L, MEMBER_ID)).willReturn(Optional.of(target));
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(oldDefault));
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);

    addressService.changeDefault(MEMBER_ID, 2L);

    assertThat(target.isDefault()).isTrue();
    assertThat(oldDefault.isDefault()).isFalse();
    then(sellerAddressEventFactory).shouldHaveNoInteractions();
    then(outboxPublisher).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("이미 기본인 배송지를 다시 기본으로 하면 바뀐 게 없어 판매자 확인 · 발행 없이 끝남")
  void changeDefault_alreadyDefault_noPublish() {
    Address target = address(2L, true);
    given(memberRepository.findByIdForUpdate(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.findByIdAndMemberId(2L, MEMBER_ID)).willReturn(Optional.of(target));

    AddressResult result = addressService.changeDefault(MEMBER_ID, 2L);

    assertThat(result.isDefault()).isTrue();
    then(addressRepository).should(never()).findByMemberIdAndIsDefaultTrue(any());
    then(sellerRepository).shouldHaveNoInteractions();
    then(sellerAddressEventFactory).shouldHaveNoInteractions();
    then(outboxPublisher).shouldHaveNoInteractions();
  }
}
