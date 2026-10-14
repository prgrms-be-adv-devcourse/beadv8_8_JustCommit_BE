package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.MemberAccount;
import com.justcommit.backend.member.MemberAddress;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.domain.SystemAccount;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
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
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class MemberQueryServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AddressRepository addressRepository;

  @Mock
  private SellerRepository sellerRepository;

  @Mock
  private AccountCipher accountCipher;

  @InjectMocks
  private MemberQueryService memberQueryService;

  private static final String ACCOUNT_NO = "12345678901234";
  private static final Long MEMBER_ID = 1L;
  private static final Long ADDRESS_ID = 10L;

  // 회원 1의 기본 배송지 (id는 DB가 채워 주는 값이라 직접 주입)
  private Address address() {
    Member member = Member.createLocal("test@gmail.com", "encoded", "떡볶이", "01012345678",
            "004", "v1:encrypted-account", "홍길동");
    ReflectionTestUtils.setField(member, "id", MEMBER_ID);

    Address address = Address.createDefault(member, "홍길동", "01087654321",
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

  @Test
  @DisplayName("홀딩 계정 ID 조회")
  void getHoldingMemberId() {
    Member system = mock(Member.class);
    given(system.getRole()).willReturn(Role.SYSTEM);
    given(system.getId()).willReturn(6L);
    given(memberRepository.findByEmail(SystemAccount.HOLDING.email())).willReturn(Optional.of(system));

    assertThat(memberQueryService.getHoldingMemberId()).isEqualTo(6L);
  }

  @Test
  @DisplayName("매출 계정 ID 조회")
  void getRevenueMemberId() {
    Member system = mock(Member.class);
    given(system.getRole()).willReturn(Role.SYSTEM);
    given(system.getId()).willReturn(7L);
    given(memberRepository.findByEmail(SystemAccount.REVENUE.email())).willReturn(Optional.of(system));

    assertThat(memberQueryService.getRevenueMemberId()).isEqualTo(7L);
  }

  @Test
  @DisplayName("시스템 계정이 없으면 서버 설정 문제로 IllegalStateException")
  void systemAccountMissing() {
    given(memberRepository.findByEmail(SystemAccount.HOLDING.email())).willReturn(Optional.empty());

    assertThatThrownBy(() -> memberQueryService.getHoldingMemberId())
            .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("같은 이메일이라도 SYSTEM 권한이 아니면 반환하지 않음(일반 회원에게 돈이 가는 것 방지)")
  void notSystemRole() {
    Member normal = mock(Member.class);
    given(normal.getRole()).willReturn(Role.MEMBER);
    given(memberRepository.findByEmail(SystemAccount.HOLDING.email())).willReturn(Optional.of(normal));

    assertThatThrownBy(() -> memberQueryService.getHoldingMemberId())
            .isInstanceOf(IllegalStateException.class);
  }

  // 판매자 여부
  @Test
  @DisplayName("판매자로 등록된 회원이면 true")
  void isSeller_true() {
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(true);

    assertThat(memberQueryService.isSeller(MEMBER_ID)).isTrue();
  }

  @Test
  @DisplayName("판매자로 등록되지 않았거나 없는 회원이면 false")
  void isSeller_false() {
    given(sellerRepository.existsById(MEMBER_ID)).willReturn(false);

    assertThat(memberQueryService.isSeller(MEMBER_ID)).isFalse();
  }

  // 닉네임
  @Test
  @DisplayName("회원 닉네임을 반환한다")
  void getNickname() {
    Member member = Member.createLocal("test@gmail.com", "encoded", "떡볶이", "01012345678",
            "004", "v1:encrypted-account", "홍길동");
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));

    assertThat(memberQueryService.getNickname(MEMBER_ID)).isEqualTo("떡볶이");
  }

  @Test
  @DisplayName("회원이 없으면 닉네임 조회 시 MEMBER_NOT_FOUND")
  void getNickname_notFound() {
    given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() -> memberQueryService.getNickname(MEMBER_ID))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
  }
}
