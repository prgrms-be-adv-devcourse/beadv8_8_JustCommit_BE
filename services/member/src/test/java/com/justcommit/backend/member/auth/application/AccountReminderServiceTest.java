package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.infrastructure.mail.AccountReminderMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendResult;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore.SendStatus;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mail.MailSendException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

  @InjectMocks
  private AccountReminderService service;

  private void givenSlot(SendStatus status) {
    given(store.tryAcquireSend(EMAIL, EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW))
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
    then(store).should(never()).releaseSend(anyString(), anyString());
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
        .tryAcquireSend(EMAIL, EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW);
    then(mailSender).shouldHaveNoInteractions();
    then(store).should(never()).releaseSend(anyString(), anyString());
  }

  @Test
  @DisplayName("재발송 제한 · 하루 한도에 걸리면 회원 조회 · 메일 없이 끝난다")
  void limited() {
    given(store.tryAcquireSend(EMAIL, EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW))
        .willReturn(new SendResult(SendStatus.COOLDOWN, 42, null), new SendResult(SendStatus.DAILY_LIMIT, 3600, null));

    service.sendReminder(EMAIL);
    service.sendReminder(EMAIL);

    then(memberRepository).shouldHaveNoInteractions();
    then(mailSender).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("SMTP가 실패하면 예외 없이 끝나고 이 요청의 예약만 되돌린다")
  void smtpFailureReleasesOwnSlot() {
    givenSlot(SendStatus.ACQUIRED);
    Member local = member(MemberStatus.ACTIVE, Provider.LOCAL);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(local));
    willThrow(new MailSendException("fail")).given(mailSender).send(EMAIL, Provider.LOCAL);

    service.sendReminder(EMAIL);

    then(store).should().releaseSend(EMAIL, REQUEST_ID);
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
    given(store.tryAcquireSend(EMAIL, EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW))
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
  @DisplayName("SMTP 실패 뒤 예약을 되돌리다 Redis 장애가 나면 그대로 올라간다 (성공으로 감추지 않음)")
  void releaseFailurePropagates() {
    givenSlot(SendStatus.ACQUIRED);
    Member local = member(MemberStatus.ACTIVE, Provider.LOCAL);
    given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(local));
    willThrow(new MailSendException("fail")).given(mailSender).send(EMAIL, Provider.LOCAL);
    willThrow(new RedisConnectionFailureException("redis down")).given(store).releaseSend(EMAIL, REQUEST_ID);

    assertThatThrownBy(() -> service.sendReminder(EMAIL)).isInstanceOf(RedisConnectionFailureException.class);
  }
}
