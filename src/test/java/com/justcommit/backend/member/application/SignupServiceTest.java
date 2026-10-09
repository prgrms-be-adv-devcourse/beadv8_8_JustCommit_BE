package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.*;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.infrastructure.repository.AddressRepository;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AddressRepository addressRepository;

  @Mock
  private EmailVerificationRedisStore verificationStore;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Mock
  private AccountCipher accountCipher;

  @InjectMocks
  private SignupService signupService;

  private static final String EMAIL = "test@gmail.com";
  private static final String PASSWORD = "plant1234";
  private static final String NICKNAME = "떡볶이";
  private static final String PHONE = "01012345678";
  private static final String BANK_CODE = "004";
  private static final String ACCOUNT_NO = "12345678901234";
  private static final String ACCOUNT_HOLDER = "홍길동";

  // 배송지
  private static final String RECIPIENT_NAME = "홍길동";
  private static final String RECIPIENT_PHONE = "01087654321";
  private static final String ZIPCODE = "06236";
  private static final String ADDRESS1 = "서울 강남구 테헤란로 123";
  private static final String ADDRESS2 = "101동 1001호";
  private static final String ADDRESS_NAME = "집";

  private SignupCommand command() {
    return command(EMAIL);
  }

  private SignupCommand command(String email) {
    return new SignupCommand(email, PASSWORD, NICKNAME, PHONE,
            BANK_CODE, ACCOUNT_NO, ACCOUNT_HOLDER,
            RECIPIENT_NAME, RECIPIENT_PHONE, ZIPCODE, ADDRESS1, ADDRESS2, ADDRESS_NAME);
  }

  // saveAndFlush 시 DB가 id를 채워 주는 동작
  private void givenMemberSaved() {
    given(memberRepository.saveAndFlush(any(Member.class))).willAnswer(invocation -> {
      Member member = invocation.getArgument(0);
      ReflectionTestUtils.setField(member, "id", 1L);
      return member;
    });
  }

  @Test
  @DisplayName("회원(정규화 이메일·암호화 비밀번호·계좌)과 기본 배송지를 저장하고 인증 완료 기록 삭제")
  void signup_success() {
    // given
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(passwordEncoder.encode(PASSWORD)).willReturn("encoded-password");
    given(accountCipher.encrypt(ACCOUNT_NO)).willReturn("v1:encrypted-account");
    givenMemberSaved();

    // when: 앞뒤 공백·대문자가 섞인 이메일로 요청
    SignupResult result = signupService.signup(command("  Test@Gmail.com "));

    // then: 응답
    assertThat(result.memberId()).isEqualTo(1L);
    assertThat(result.nickname()).isEqualTo(NICKNAME);

    // then: 저장된 회원 값
    ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
    then(memberRepository).should().saveAndFlush(memberCaptor.capture());
    Member savedMember = memberCaptor.getValue();
    assertThat(savedMember.getEmail()).isEqualTo(EMAIL);
    assertThat(savedMember.getPassword()).isEqualTo("encoded-password");
    assertThat(savedMember.getRole()).isEqualTo(Role.MEMBER);
    assertThat(savedMember.getProvider()).isEqualTo(Provider.LOCAL);
    assertThat(savedMember.getStatus()).isEqualTo(MemberStatus.ACTIVE);

    // then: 계좌는 암호문으로 저장 (원문 계좌번호는 저장되지 않음)
    assertThat(savedMember.getBankCode()).isEqualTo(BANK_CODE);
    assertThat(savedMember.getAccountNoEnc()).isEqualTo("v1:encrypted-account");
    assertThat(savedMember.getAccountHolder()).isEqualTo(ACCOUNT_HOLDER);
    assertThat(savedMember.getAccountUpdatedAt()).isNotNull();

    // then: 입력한 배송지를 저장된 회원 id의 기본 배송지로 저장
    ArgumentCaptor<Address> addressCaptor = ArgumentCaptor.forClass(Address.class);
    then(addressRepository).should().save(addressCaptor.capture());
    Address savedAddress = addressCaptor.getValue();
    assertThat(savedAddress.getMemberId()).isEqualTo(1L);
    assertThat(savedAddress.isDefault()).isTrue();
    assertThat(savedAddress.getRecipientName()).isEqualTo(RECIPIENT_NAME);
    assertThat(savedAddress.getRecipientPhone()).isEqualTo(RECIPIENT_PHONE);
    assertThat(savedAddress.getZipcode()).isEqualTo(ZIPCODE);
    assertThat(savedAddress.getAddress1()).isEqualTo(ADDRESS1);
    assertThat(savedAddress.getAddress2()).isEqualTo(ADDRESS2);
    assertThat(savedAddress.getAddressName()).isEqualTo(ADDRESS_NAME);

    // then: 인증 완료 기록 삭제
    then(verificationStore).should().deleteVerified(EMAIL);
  }

  @Test
  @DisplayName("배송지 저장이 실패하면 예외가 그대로 올라가고 인증 완료 기록은 남겨 둠 (다시 가입 가능)")
  void signup_addressSaveFails_keepsVerification() {
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(passwordEncoder.encode(PASSWORD)).willReturn("encoded-password");
    givenMemberSaved();
    willThrow(new RuntimeException("address save failed"))
            .given(addressRepository).save(any(Address.class));

    assertThatThrownBy(() -> signupService.signup(command()))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("address save failed");

    then(verificationStore).should(never()).deleteVerified(anyString());
  }

  @Test
  @DisplayName("이메일 인증을 안 했으면 EMAIL_NOT_VERIFIED, DB는 조회하지 않음")
  void signup_notVerified() {
    given(verificationStore.isVerified(EMAIL)).willReturn(false);

    assertThatThrownBy(() -> signupService.signup(command()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.EMAIL_NOT_VERIFIED);

    then(memberRepository).shouldHaveNoInteractions();
    then(addressRepository).shouldHaveNoInteractions();
    then(accountCipher).shouldHaveNoInteractions(); // 거부되는 요청은 암호화하지 않음
  }

  @Test
  @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL, 회원·배송지 모두 저장하지 않음")
  void signup_duplicateEmail() {
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(memberRepository.existsByEmail(EMAIL)).willReturn(true);

    assertThatThrownBy(() -> signupService.signup(command()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);

    then(memberRepository).should(never()).saveAndFlush(any());
    then(addressRepository).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("사용 중인 닉네임이면 DUPLICATE_NICKNAME, 저장하지 않음")
  void signup_duplicateNickname() {
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(memberRepository.existsByNickname(NICKNAME)).willReturn(true);

    assertThatThrownBy(() -> signupService.signup(command()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.DUPLICATE_NICKNAME);

    then(memberRepository).should(never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("이미 가입된 휴대폰 번호면 DUPLICATE_PHONE, 저장하지 않음")
  void signup_duplicatePhone() {
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(memberRepository.existsByPhone(PHONE)).willReturn(true);

    assertThatThrownBy(() -> signupService.signup(command()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.DUPLICATE_PHONE);

    then(memberRepository).should(never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("동시 가입으로 DB UNIQUE 위반 시 DUPLICATE_NICKNAME으로 변환, 배송지는 저장하지 않음")
  void signup_uniqueViolation_nickname() {
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(passwordEncoder.encode(anyString())).willReturn("encoded-password");
    willThrow(new DataIntegrityViolationException("could not execute statement",
            new RuntimeException("duplicate key value violates unique constraint \"uk_member_nickname\"")))
            .given(memberRepository).saveAndFlush(any(Member.class));

    assertThatThrownBy(() -> signupService.signup(command()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.DUPLICATE_NICKNAME);

    then(addressRepository).shouldHaveNoInteractions();
    then(verificationStore).should(never()).deleteVerified(anyString());
  }

  @Test
  @DisplayName("알 수 없는 제약 위반이면 변환하지 않고 원래 예외를 그대로 던짐")
  void signup_uniqueViolation_unknown() {
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(passwordEncoder.encode(anyString())).willReturn("encoded-password");
    willThrow(new DataIntegrityViolationException("could not execute statement",
            new RuntimeException("violates check constraint \"member_role_check\"")))
            .given(memberRepository).saveAndFlush(any(Member.class));

    assertThatThrownBy(() -> signupService.signup(command()))
            .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("이미 있는 닉네임이면 사용 불가(false)")
  void isNicknameAvailable_taken() {
    given(memberRepository.existsByNickname(NICKNAME)).willReturn(true);

    assertThat(signupService.isNicknameAvailable(NICKNAME)).isFalse();
  }
}
