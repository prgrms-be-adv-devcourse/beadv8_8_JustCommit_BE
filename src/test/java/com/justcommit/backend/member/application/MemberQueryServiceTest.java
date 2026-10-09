package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberAccount;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MemberQueryServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AddressRepository addressRepository;

  @Mock
  private AccountCipher accountCipher;

  @InjectMocks
  private MemberQueryService memberQueryService;

  private static final String ACCOUNT_NO = "12345678901234";
  private static final Long MEMBER_ID = 1L;
  private static final Long ADDRESS_ID = 10L;

  // 회원 1의 기본 배송지 (id는 DB가 채워 주는 값이라 직접 주입)
  private Address address() {
    Address address = Address.createDefault(MEMBER_ID, "홍길동", "01087654321",
            "06236", "서울 강남구 테헤란로 123", "101동 1001호", "집");
    ReflectionTestUtils.setField(address, "id", ADDRESS_ID);
    return address;
  }

  @Test
  @DisplayName("계좌 조회: 암호문을 복호화해 은행 코드·계좌번호·예금주를 반환")
  void getAccount_success() {
    Member member = Member.createLocal("test@gmail.com", "encoded", "떡볶이", "01012345678",
            "004", "v1:encrypted-account", "홍길동");
    given(memberRepository.findById(1L)).willReturn(Optional.of(member));
    given(accountCipher.decrypt("v1:encrypted-account")).willReturn(ACCOUNT_NO);

    MemberAccount account = memberQueryService.getAccount(1L);

    assertThat(account.bankCode()).isEqualTo("004");
    assertThat(account.accountNo()).isEqualTo(ACCOUNT_NO);
    assertThat(account.accountHolder()).isEqualTo("홍길동");
  }

  @Test
  @DisplayName("없는 회원이면 MEMBER_NOT_FOUND")
  void getAccount_memberNotFound() {
    given(memberRepository.findById(999L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> memberQueryService.getAccount(999L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
  }

  @Test
  @DisplayName("MemberAccount를 문자열로 출력하면 계좌번호는 뒤 4자리만 보임")
  void memberAccount_toStringMasked() {
    MemberAccount account = new MemberAccount("004", ACCOUNT_NO, "홍길동");

    assertThat(account.toString())
            .doesNotContain(ACCOUNT_NO)
            .contains("****1234");
  }

  @Test
  @DisplayName("배송지 조회: 본인 배송지면 주문용 배송 정보를 반환")
  void getAddress_success() {
    given(addressRepository.findByIdAndMemberId(ADDRESS_ID, MEMBER_ID)).willReturn(Optional.of(address()));

    MemberAddress result = memberQueryService.getAddress(MEMBER_ID, ADDRESS_ID);

    assertThat(result.addressId()).isEqualTo(ADDRESS_ID);
    assertThat(result.recipientName()).isEqualTo("홍길동");
    assertThat(result.recipientPhone()).isEqualTo("01087654321");
    assertThat(result.zipcode()).isEqualTo("06236");
    assertThat(result.address1()).isEqualTo("서울 강남구 테헤란로 123");
    assertThat(result.address2()).isEqualTo("101동 1001호");
  }

  @Test
  @DisplayName("다른 회원의 배송지 id면 ADDRESS_NOT_FOUND (존재 여부를 드러내지 않음)")
  void getAddress_notOwner() {
    Long otherMemberId = 2L;
    given(addressRepository.findByIdAndMemberId(ADDRESS_ID, otherMemberId)).willReturn(Optional.empty());

    assertThatThrownBy(() -> memberQueryService.getAddress(otherMemberId, ADDRESS_ID))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ADDRESS_NOT_FOUND);
  }

  @Test
  @DisplayName("기본 배송지 조회: 회원의 기본 배송지를 반환")
  void getDefaultAddress_success() {
    given(addressRepository.findByMemberIdAndIsDefaultTrue(MEMBER_ID)).willReturn(Optional.of(address()));

    MemberAddress result = memberQueryService.getDefaultAddress(MEMBER_ID);

    assertThat(result.addressId()).isEqualTo(ADDRESS_ID);
    assertThat(result.zipcode()).isEqualTo("06236");
  }

  @Test
  @DisplayName("기본 배송지가 없으면 ADDRESS_NOT_FOUND")
  void getDefaultAddress_notFound() {
    given(addressRepository.findByMemberIdAndIsDefaultTrue(999L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> memberQueryService.getDefaultAddress(999L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.ADDRESS_NOT_FOUND);
  }
}
