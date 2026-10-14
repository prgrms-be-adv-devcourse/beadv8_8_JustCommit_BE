package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MyInfoServiceTest {

  private static final Long MEMBER_ID = 1L;

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AddressRepository addressRepository;

  @Mock
  private AccountCipher accountCipher;

  @InjectMocks
  private MyInfoService myInfoService;

  @Test
  @DisplayName("내 정보 조회 - 계좌번호는 뒤 4자리만, 기본 배송지 포함")
  void getMyInfo() {
    Member member = givenMember();
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
    given(accountCipher.decrypt("encrypted")).willReturn("12345678901234");

    Address address = mock(Address.class);
    given(address.getId()).willReturn(10L);
    given(address.getAddressName()).willReturn("집");
    given(address.getRecipientName()).willReturn("홍길동");
    given(address.getRecipientPhone()).willReturn("01012345678");
    given(address.getZipcode()).willReturn("06236");
    given(address.getAddress1()).willReturn("서울시 강남구 테헤란로 1");
    given(address.getAddress2()).willReturn("101호");
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(address));

    MyInfoResult result = myInfoService.getMyInfo(MEMBER_ID);

    assertThat(result.memberId()).isEqualTo(MEMBER_ID);
    assertThat(result.email()).isEqualTo("test@example.com");
    assertThat(result.nickname()).isEqualTo("테스트");
    assertThat(result.role()).isEqualTo("MEMBER");
    assertThat(result.provider()).isEqualTo("LOCAL");

    // 원문 계좌번호가 결과에 들어가지 않는지 확인
    assertThat(result.account().maskedAccountNo()).isEqualTo("****1234");
    assertThat(result.account().bankCode()).isEqualTo("004");

    assertThat(result.defaultAddress().addressId()).isEqualTo(10L);
    assertThat(result.defaultAddress().addressName()).isEqualTo("집");
    assertThat(result.defaultAddress().address2()).isEqualTo("101호");
  }

  @Test
  @DisplayName("기본 배송지가 없으면 404 대신 null")
  void noDefaultAddress() {
    Member member = givenMember();
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));
    given(accountCipher.decrypt("encrypted")).willReturn("12345678901234");
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.empty());

    MyInfoResult result = myInfoService.getMyInfo(MEMBER_ID);

    assertThat(result.defaultAddress()).isNull();
    assertThat(result.account().maskedAccountNo()).isEqualTo("****1234");
  }

  @Test
  @DisplayName("회원이 없으면 404 MEMBER_NOT_FOUND, 복호화·배송지 조회는 하지 않음")
  void memberNotFound() {
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> myInfoService.getMyInfo(MEMBER_ID))
            .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));

    verifyNoInteractions(accountCipher, addressRepository);
  }

  @Test
  @DisplayName("마스킹 - 뒤 4자리만 남김")
  void mask() {
    assertThat(MyInfoService.mask("1234567890")).isEqualTo("****7890"); // 최소 10자리
    assertThat(MyInfoService.mask("12345678901234")).isEqualTo("****1234");  // 최대 14자리
  }

  // Member는 정적 팩토리 인자가 많아서 필요한 getter만 가짜로 만듦
  private Member givenMember() {
    Member member = mock(Member.class);
    given(member.getId()).willReturn(MEMBER_ID);
    given(member.getEmail()).willReturn("test@example.com");
    given(member.getNickname()).willReturn("테스트");
    given(member.getPhone()).willReturn("01012345678");
    given(member.getRole()).willReturn(Role.MEMBER);
    given(member.getProvider()).willReturn(Provider.LOCAL);
    given(member.getBankCode()).willReturn("004");
    given(member.getAccountNoEnc()).willReturn("encrypted");
    given(member.getAccountHolder()).willReturn("홍길동");
    return member;
  }
}
