package com.justcommit.backend.member.application;

import com.justcommit.backend.member.domain.SystemAccount;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SystemAccountInitializerTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AccountCipher accountCipher;

  @InjectMocks
  private SystemAccountInitializer initializer;

  @Test
  @DisplayName("시스템 계정이 없으면 SYSTEM 권한·비밀번호 없음으로 생성")
  void createWhenMissing() {
    given(memberRepository.existsByEmail(SystemAccount.HOLDING.email())).willReturn(false);
    given(accountCipher.encrypt(SystemAccountInitializer.ACCOUNT_NO)).willReturn("encrypted");

    initializer.createIfMissing(SystemAccount.HOLDING);

    ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
    verify(memberRepository).saveAndFlush(captor.capture());
    Member saved = captor.getValue();

    assertThat(saved.getEmail()).isEqualTo(SystemAccount.HOLDING.email());
    assertThat(saved.getNickname()).isEqualTo(SystemAccount.HOLDING.nickname());
    assertThat(saved.getRole()).isEqualTo(Role.SYSTEM);
    assertThat(saved.getPassword()).isNull();
    assertThat(saved.getAccountNoEnc()).isEqualTo("encrypted");
  }

  @Test
  @DisplayName("이미 있으면 건너뜀(재시작해도 중복 생성x)")
  void skipWhenExists() {
    given(memberRepository.existsByEmail(SystemAccount.HOLDING.email())).willReturn(true);

    initializer.createIfMissing(SystemAccount.HOLDING);

    verify(memberRepository, never()).saveAndFlush(any());
    verifyNoInteractions(accountCipher);
  }

  @Test
  @DisplayName("DB 제약 위반이 나도 예외를 던지지 않음(앱 시작을 막지 않음)")
  void notThrowOnConstraintViolation() {
    given(memberRepository.existsByEmail(SystemAccount.HOLDING.email())).willReturn(false);
    given(accountCipher.encrypt(SystemAccountInitializer.ACCOUNT_NO)).willReturn("encrypted");
    given(memberRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("member_role_check"));

    assertThatCode(() -> initializer.createIfMissing(SystemAccount.HOLDING))
            .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("run은 모든 시스템 계정(홀딩·매출)을 확인")
  void runChecksAllAccounts() {
    given(memberRepository.existsByEmail(any())).willReturn(true);

    initializer.run(null);

    verify(memberRepository).existsByEmail(SystemAccount.HOLDING.email());
    verify(memberRepository).existsByEmail(SystemAccount.REVENUE.email());
  }
}
