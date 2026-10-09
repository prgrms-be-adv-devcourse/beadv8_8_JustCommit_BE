package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.redis.EmailVerificationRedisStore;
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

  private SignupCommand command() {
    return new SignupCommand(EMAIL, PASSWORD, NICKNAME, PHONE, BANK_CODE, ACCOUNT_NO, ACCOUNT_HOLDER);
  }

  @Test
  @DisplayName("가입 성공: 정규화한 이메일, 암호화한 비밀번호·계좌번호로 저장하고 인증 완료 기록 삭제")
  void signup_success() {
    // given
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(passwordEncoder.encode(PASSWORD)).willReturn("encoded-password");
    given(accountCipher.encrypt(ACCOUNT_NO)).willReturn("v1:encrypted-account");
    given(memberRepository.saveAndFlush(any(Member.class))).willAnswer(invocation -> {
      Member member = invocation.getArgument(0);
      ReflectionTestUtils.setField(member, "id", 1L); // DB가 id를 채워 주는 동작
      return member;
    });

    // when: 앞뒤 공백·대문자가 섞인 이메일로 요청
    SignupResult result = signupService.signup(
            new SignupCommand("  Test@Gmail.com ", PASSWORD, NICKNAME, PHONE, BANK_CODE, ACCOUNT_NO, ACCOUNT_HOLDER));

    // then: 응답
    assertThat(result.memberId()).isEqualTo(1L);
    assertThat(result.nickname()).isEqualTo(NICKNAME);

    // then: 저장된 회원 값
    ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
    then(memberRepository).should().saveAndFlush(captor.capture());
    Member saved = captor.getValue();
    assertThat(saved.getEmail()).isEqualTo(EMAIL);
    assertThat(saved.getPassword()).isEqualTo("encoded-password");
    assertThat(saved.getRole()).isEqualTo(Role.MEMBER);
    assertThat(saved.getProvider()).isEqualTo(Provider.LOCAL);
    assertThat(saved.getStatus()).isEqualTo(MemberStatus.ACTIVE);

    // then: 계좌는 암호문으로 저장 (원문 계좌번호는 저장되지 않음)
    assertThat(saved.getBankCode()).isEqualTo(BANK_CODE);
    assertThat(saved.getAccountNoEnc()).isEqualTo("v1:encrypted-account");
    assertThat(saved.getAccountHolder()).isEqualTo(ACCOUNT_HOLDER);
    assertThat(saved.getAccountUpdatedAt()).isNotNull();

    // then: 인증 완료 기록 삭제
    then(verificationStore).should().deleteVerified(EMAIL);
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
    then(accountCipher).shouldHaveNoInteractions(); // 거부되는 요청은 암호화하지 않음
  }

  @Test
  @DisplayName("이미 가입된 이메일이면 DUPLICATE_EMAIL, 저장하지 않음")
  void signup_duplicateEmail() {
    given(verificationStore.isVerified(EMAIL)).willReturn(true);
    given(memberRepository.existsByEmail(EMAIL)).willReturn(true);

    assertThatThrownBy(() -> signupService.signup(command()))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);

    then(memberRepository).should(never()).saveAndFlush(any());
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
  @DisplayName("동시 가입으로 DB UNIQUE 위반 시 제약 이름을 보고 DUPLICATE_NICKNAME으로 변환")
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
