package com.justcommit.backend.member.application;

import com.justcommit.backend.member.MemberRegisteredEvent;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.Role;
import com.justcommit.backend.member.domain.SystemAccount;
import com.justcommit.backend.member.infrastructure.crypto.AccountCipher;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemAccountInitializerTest {

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AccountCipher accountCipher;

  @Mock
  private TransactionTemplate transactionTemplate;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  @InjectMocks
  private SystemAccountInitializer initializer;

  // TransactionTemplate이 실제 생성 코드를 바로 실행하도록 설정
  @BeforeEach
  void runCallbackImmediately() {
    given(transactionTemplate.execute(any())).willAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(null);
    });
  }

  @Test
  @DisplayName("시스템 계정이 없으면 SYSTEM 권한으로 생성하고 회원 생성 이벤트(systemAccount=true) 발행")
  void createWhenMissing() {
    given(memberRepository.existsByEmail(SystemAccount.HOLDING.email())).willReturn(false);
    given(accountCipher.encrypt(SystemAccountInitializer.ACCOUNT_NO)).willReturn("encrypted");
    // DB가 id를 채워 주는 것처럼 저장 시 id 6을 넣어서 돌려줌
    given(memberRepository.saveAndFlush(any())).willAnswer(invocation -> {
      Member member = invocation.getArgument(0);
      ReflectionTestUtils.setField(member, "id", 6L);
      return member;
    });

    initializer.createIfMissing(SystemAccount.HOLDING);

    ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
    verify(memberRepository).saveAndFlush(captor.capture());
    Member saved = captor.getValue();

    assertThat(saved.getEmail()).isEqualTo(SystemAccount.HOLDING.email());
    assertThat(saved.getRole()).isEqualTo(Role.SYSTEM);
    assertThat(saved.getPassword()).isNull();
    assertThat(saved.getAccountNoEnc()).isEqualTo("encrypted");

    verify(eventPublisher).publishEvent(new MemberRegisteredEvent(6L, true));
  }

  @Test
  @DisplayName("이미 있으면 건너뜀(재시작해도 중복 생성x)")
  void skipWhenExists() {
    given(memberRepository.existsByEmail(SystemAccount.HOLDING.email())).willReturn(true);

    initializer.createIfMissing(SystemAccount.HOLDING);

    verify(memberRepository, never()).saveAndFlush(any());
    verifyNoInteractions(accountCipher, eventPublisher);
  }

  @Test
  @DisplayName("DB 제약 위반이 나도 예외를 던지지 않음(이벤트도 발행하지 않음)")
  void notThrowOnConstraintViolation() {
    given(memberRepository.existsByEmail(SystemAccount.HOLDING.email())).willReturn(false);
    given(accountCipher.encrypt(SystemAccountInitializer.ACCOUNT_NO)).willReturn("encrypted");
    given(memberRepository.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("member_role_check"));

    assertThatCode(() -> initializer.createIfMissing(SystemAccount.HOLDING))
            .doesNotThrowAnyException();

    verifyNoInteractions(eventPublisher);
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
