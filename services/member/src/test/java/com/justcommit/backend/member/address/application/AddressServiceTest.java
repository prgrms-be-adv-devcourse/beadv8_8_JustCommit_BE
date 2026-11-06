package com.justcommit.backend.member.address.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
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
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
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
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
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
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(mock(Member.class)));
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.empty());
    givenSaveReturnsArgument();

    AddressResult result = addressService.addAddress(MEMBER_ID, command(false));

    assertThat(result.isDefault()).isTrue();
  }

  @Test
  @DisplayName("배송지가 이미 10개면 등록할 수 없음")
  void addAddress_limitExceeded() {
    given(addressRepository.countByMemberId(MEMBER_ID)).willReturn(10L);

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
    given(addressRepository.findByIdAndMemberId(2L, MEMBER_ID)).willReturn(Optional.of(target));
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(oldDefault));

    AddressResult result = addressService.changeDefault(MEMBER_ID, 2L);

    assertThat(result.addressId()).isEqualTo(2L);
    assertThat(target.isDefault()).isTrue();
    assertThat(oldDefault.isDefault()).isFalse();
  }

  @Test
  @DisplayName("내 배송지가 아니면 404 ADDRESS_NOT_FOUND")
  void changeDefault_notFound() {
    given(addressRepository.findByIdAndMemberId(99L, MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> addressService.changeDefault(MEMBER_ID, 99L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ADDRESS_NOT_FOUND);
  }
}
