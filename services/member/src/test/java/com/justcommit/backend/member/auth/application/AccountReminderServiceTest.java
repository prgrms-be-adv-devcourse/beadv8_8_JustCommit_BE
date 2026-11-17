package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.domain.EmailSendQuota;
import com.justcommit.backend.member.auth.infrastructure.mail.AccountReminderMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendResult;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendStatus;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;

// 가입 계정 안내 서비스 (#412)
@ExtendWith(MockitoExtension.class)
class AccountReminderServiceTest {

  private static final String EMAIL = "user@example.com";
  private static final String REQUEST_ID = "request-1";

  @Mock
  private EmailVerificationRedisStore store;

  @Mock
  private MemberRepository memberRepository;

  @Mock
  private AccountReminderMailSender mailSender;

  private AccountReminderService service;

  // 기본은 제출한 작업을 바로 실행하는 실행기 (기존 흐름을 순서대로 검증). 비동기 · 제출 거절은 각 테스트에서 따로 만든다 (#421)
  @BeforeEach
  void setUp() {
    service = serviceWith(new SyncTaskExecutor());
  }

  private AccountReminderService serviceWith(TaskExecutor mailTaskExecutor) {
    return new AccountReminderService(store, memberRepository, mailSender, mailTaskExecutor);
  }

  // 대기열이 가득 찬 실행기처럼 제출을 항상 거절
  private static TaskExecutor rejectingExecutor() {
    return task -> {
      throw new TaskRejectedException("mail executor full");
    };
  }

  // 실제 스레드 풀 (스레드 1개). 테스트가 끝나면 shutdown
  private static ThreadPoolTaskExecutor realExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.setThreadNamePrefix("mail-test-");
    executor.initialize();
    return executor;
  }

  private void givenSlot(SendStatus status) {
    given(store.tryAcquireSend(EmailSendQuota.ACCOUNT_REMINDER, EMAIL, EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW))
        .willReturn(new SendResult(status, status == SendStatus.ACQUIRED ? 0 : 30, status == SendStatus.ACQUIRED ? REQUEST_ID : null));
  }

  private Member member(MemberStatus status, Provider provider) {
    Member member = mock(Member.class);
    lenient().when(member.getStatus()).thenReturn(status);
    lenient().when(member.getProvider()).thenReturn(provider);
    return member;
  }

  @Test
  @DisplayName("활동 중인 이메일 로그인 회원이면 이메일 로그인 안내를 보낸다")
  void localMember() {
    givenSlot(SendStatus.ACQUIRED);
    Member local = member(MemberStatus.ACTIVE, Provider.LOCAL);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(local));

    service.sendReminder(EMAIL);

    then(mailSender).should().send(EMAIL, Provider.LOCAL);
    then(store).should(never()).releaseReminderSend(anyString(), anyString());
  }

  @Test
  @DisplayName("활동 중인 카카오 회원이면 카카오 로그인 안내를 보낸다")
  void kakaoMember() {
    givenSlot(SendStatus.ACQUIRED);
    Member kakao = member(MemberStatus.ACTIVE, Provider.KAKAO);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(kakao));

    service.sendReminder(EMAIL);

    then(mailSender).should().send(EMAIL, Provider.KAKAO);
  }

  @Test
  @DisplayName("미가입 · 네이버 · 비활성 · 탈퇴는 메일 없이 끝나고, 예약은 되돌리지 않는다 (제한 상태가 가입 여부와 관계없이 쌓이게)")
  void notTarget() {
    givenSlot(SendStatus.ACQUIRED);
    Member naver = member(MemberStatus.ACTIVE, Provider.NAVER);
    Member inactive = member(MemberStatus.INACTIVE, Provider.LOCAL);
    Member deleted = member(MemberStatus.DELETED, Provider.LOCAL);
    // 차례로: 미가입 → 네이버 → 비활성 → 탈퇴
    given(memberRepository.findByEmail(EMAIL))
        .willReturn(Optional.empty())
        .willReturn(Optional.of(naver))
        .willReturn(Optional.of(inactive))
        .willReturn(Optional.of(deleted));

    for (int i = 0; i < 4; i++) {
      service.sendReminder(EMAIL);
    }

    then(store).should(times(4))
        .tryAcquireSend(EmailSendQuota.ACCOUNT_REMINDER, EMAIL, EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW);
    then(mailSender).shouldHaveNoInteractions();
    then(store).should(never()).releaseReminderSend(anyString(), anyString());
  }

  @Test
  @DisplayName("재발송 제한 · 하루 한도에 걸리면 회원 조회 · 메일 없이 끝난다")
  void limited() {
    given(store.tryAcquireSend(EmailSendQuota.ACCOUNT_REMINDER, EMAIL, EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW))
        .willReturn(new SendResult(SendStatus.COOLDOWN, 42, null), new SendResult(SendStatus.DAILY_LIMIT, 3600, null));

    service.sendReminder(EMAIL);
    service.sendReminder(EMAIL);

    then(memberRepository).shouldHaveNoInteractions();
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("SMTP가 실패하면 예외 없이 끝나고 이 요청의 예약만 되돌린다, 로그에는 이메일 · 예외 메시지 없이 예외 종류만")
  @ExtendWith(OutputCaptureExtension.class)
  void smtpFailureReleasesOwnSlot(CapturedOutput output) {
    String smtpReply = "550 5.1.1 <" + EMAIL + "> recipient rejected";
    givenSlot(SendStatus.ACQUIRED);
    Member local = member(MemberStatus.ACTIVE, Provider.LOCAL);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(local));
    willThrow(new MailSendException(smtpReply)).given(mailSender).send(EMAIL, Provider.LOCAL);

    service.sendReminder(EMAIL);

    then(store).should().releaseReminderSend(EMAIL, REQUEST_ID);
    assertThat(output.getAll()).contains("cause=MailSendException")
        .doesNotContain(EMAIL).doesNotContain(smtpReply);
  }

  // ===== 메일 작업 제출 (#421) =====

  @Test
  @DisplayName("메일 작업 제출이 거절되면 예외 없이 끝나고 이 요청의 예약만 되돌린다, 메일 없음, 로그에는 거절 종류만")
  @ExtendWith(OutputCaptureExtension.class)
  void submitRejectedReleasesOwnSlot(CapturedOutput output) {
    service = serviceWith(rejectingExecutor());
    givenSlot(SendStatus.ACQUIRED);
    Member local = member(MemberStatus.ACTIVE, Provider.LOCAL);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(local));

    service.sendReminder(EMAIL);

    then(store).should().releaseReminderSend(EMAIL, REQUEST_ID);
    then(mailSender).shouldHaveNoInteractions();
    assertThat(output.getAll()).contains("reason=TaskRejectedException").doesNotContain(EMAIL);
  }

  @Test
  @DisplayName("SMTP 호출이 끝나지 않아도 안내 요청은 먼저 반환하고, 정상 발송이면 예약을 되돌리지 않는다")
  void returnsBeforeSmtp() throws Exception {
    ThreadPoolTaskExecutor executor = realExecutor();
    CountDownLatch smtpRelease = new CountDownLatch(1);
    try {
      service = serviceWith(executor);
      givenSlot(SendStatus.ACQUIRED);
      Member local = member(MemberStatus.ACTIVE, Provider.LOCAL);
      given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(local));
      CountDownLatch smtpStarted = new CountDownLatch(1);
      CountDownLatch smtpDone = new CountDownLatch(1);
      willAnswer(invocation -> {
        smtpStarted.countDown();
        smtpRelease.await(5, TimeUnit.SECONDS);
        smtpDone.countDown();
        return null;
      }).given(mailSender).send(EMAIL, Provider.LOCAL);

      service.sendReminder(EMAIL);

      // 반환된 시점에 SMTP는 시작됐지만 아직 끝나지 않음
      assertThat(smtpStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(smtpDone.getCount()).isEqualTo(1);

      smtpRelease.countDown();
      assertThat(smtpDone.await(5, TimeUnit.SECONDS)).isTrue();
      then(store).should(after(200).never()).releaseReminderSend(anyString(), anyString());
    } finally {
      // 검증이 중간에 실패해도 막아 둔 메일 스레드를 풀고 실행기를 닫는다
      smtpRelease.countDown();
      executor.shutdown();
    }
  }

  @Test
  @DisplayName("응답 뒤 메일 스레드에서 SMTP가 실패하면 이 요청의 예약을 되돌린다")
  void asyncSmtpFailureReleasesOwnSlot() {
    ThreadPoolTaskExecutor executor = realExecutor();
    try {
      service = serviceWith(executor);
      givenSlot(SendStatus.ACQUIRED);
      Member local = member(MemberStatus.ACTIVE, Provider.LOCAL);
      given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(local));
      willThrow(new MailSendException("fail")).given(mailSender).send(EMAIL, Provider.LOCAL);

      service.sendReminder(EMAIL);

      then(store).should(timeout(5000)).releaseReminderSend(EMAIL, REQUEST_ID);
    } finally {
      executor.shutdown();
    }
  }

  @Test
  @DisplayName("이메일은 앞뒤 공백 제거, 소문자로 바꿔서 사용")
  void normalizeEmail() {
    givenSlot(SendStatus.ACQUIRED);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

    service.sendReminder("  User@Example.COM ");

    then(memberRepository).should().findByEmail(EMAIL);
  }

  // ===== 장애는 성공으로 감추지 않는다 =====

  @Test
  @DisplayName("발송 예약 중 Redis 장애는 그대로 올라간다")
  void reserveFailurePropagates() {
    given(store.tryAcquireSend(EmailSendQuota.ACCOUNT_REMINDER, EMAIL, EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW))
        .willThrow(new RedisConnectionFailureException("redis down"));

    assertThatThrownBy(() -> service.sendReminder(EMAIL)).isInstanceOf(RedisConnectionFailureException.class);
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("회원 조회 중 DB 장애는 그대로 올라간다")
  void lookupFailurePropagates() {
    givenSlot(SendStatus.ACQUIRED);
    given(memberRepository.findByEmail(EMAIL)).willThrow(new DataAccessResourceFailureException("db down"));

    assertThatThrownBy(() -> service.sendReminder(EMAIL)).isInstanceOf(DataAccessResourceFailureException.class);
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("SMTP 실패 뒤 예약을 되돌리다 Redis 장애가 나면 메일 작업 밖으로 그대로 올라간다 (성공으로 감추지 않음, 바로 실행하는 실행기라 호출자에게 보임)")
  void releaseFailurePropagates() {
    givenSlot(SendStatus.ACQUIRED);
    Member local = member(MemberStatus.ACTIVE, Provider.LOCAL);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(local));
    willThrow(new MailSendException("fail")).given(mailSender).send(EMAIL, Provider.LOCAL);
    willThrow(new RedisConnectionFailureException("redis down")).given(store).releaseReminderSend(EMAIL, REQUEST_ID);

    assertThatThrownBy(() -> service.sendReminder(EMAIL)).isInstanceOf(RedisConnectionFailureException.class);
  }
}
