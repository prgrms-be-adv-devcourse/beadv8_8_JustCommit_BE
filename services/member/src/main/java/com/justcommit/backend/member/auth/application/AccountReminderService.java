package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.domain.EmailSendQuota;
import com.justcommit.backend.member.auth.infrastructure.mail.AccountReminderMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.config.MailExecutorConfig;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 가입 계정 안내 (#412, 아이디 찾기). 받은 이메일로 가입된 계정이 있으면 로그인 방식을 메일로 안내한다.
 * - 유효한 요청은 가입 여부 · 로그인 방식 · 발송 제한 · SMTP 실패와 관계없이 같은 결과로 끝낸다 (응답으로 가입 여부가 드러나지 않게).
 *   입력 오류는 400(요청 검증), Redis · DB 장애는 감추지 않고 서버 오류로 올라간다
 * - 발송 예약은 대상인지 확인하기 전에 인증 메일과 같은 정책(EmailSendPolicy)으로 잡는다. 60초 재발송 간격은 인증 메일과
 *   공유하고, 하루 한도는 안내 전용(ACCOUNT_REMINDER)으로 따로 센다 (안내 요청이 비밀번호 재설정 한도를 소진하지 않게).
 *   대상이 아니어도 예약은 남겨 반복 요청의 제한 상태가 가입 여부와 관계없이 똑같이 쌓이게 한다
 * - 대상: 활동 중인 회원 중 지금 지원하는 로그인 방식(이메일 · 카카오)으로 가입한 계정
 * - 메일은 전용 실행기에서 보낸다 (#421). 요청은 예약 · 대상 확인 · 작업 제출까지만 하고 응답한다
 *   - 제출 거절(실행기 대기열 가득 참): 응답 전에 이 요청의 예약을 되돌리고 같은 200
 *   - SMTP 실패(MailException): 응답과 별개로 메일 스레드에서 이 요청의 예약을 되돌린다 (되돌리다 Redis 오류가 나면 그대로 올라감)
 */
@Slf4j
@Service
public class AccountReminderService {

  // 지금 안내할 수 있는 로그인 방식 (네이버는 로그인 기능이 열릴 때 안내 문구와 함께 추가)
  private static final Set<Provider> SUPPORTED_PROVIDERS = EnumSet.of(Provider.LOCAL, Provider.KAKAO);

  private final EmailVerificationRedisStore store;
  private final MemberRepository memberRepository;
  private final AccountReminderMailSender mailSender;
  private final TaskExecutor mailTaskExecutor;

  // 메일 실행기는 이름으로만 주입되는 빈이라 생성자 매개변수에 @Qualifier를 직접 붙인다
  // (Lombok 생성자는 필드의 @Qualifier를 옮기지 않아, 한정자 없이 기본 실행기가 들어갈 수 있음)
  public AccountReminderService(EmailVerificationRedisStore store,
                                MemberRepository memberRepository,
                                AccountReminderMailSender mailSender,
                                @Qualifier(MailExecutorConfig.MAIL_TASK_EXECUTOR) TaskExecutor mailTaskExecutor) {
    this.store = store;
    this.memberRepository = memberRepository;
    this.mailSender = mailSender;
    this.mailTaskExecutor = mailTaskExecutor;
  }

  public void sendReminder(String rawEmail) {
    String email = rawEmail.trim().toLowerCase(Locale.ROOT);
    EmailVerificationRedisStore.SendResult slot = store.tryAcquireSend(EmailSendQuota.ACCOUNT_REMINDER, email,
        EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW);
    if (slot.status() != EmailVerificationRedisStore.SendStatus.ACQUIRED) {
      return;
    }
    Optional<Member> target = memberRepository.findByEmail(email).filter(AccountReminderService::remindable);
    if (target.isEmpty()) {
      return;
    }
    // 메일 스레드에는 엔티티가 아니라 값만 넘긴다
    Provider provider = target.get().getProvider();
    try {
      mailTaskExecutor.execute(() -> sendReminderMail(email, provider, slot.requestId()));
    } catch (TaskRejectedException e) {
      log.warn("가입 계정 안내 메일 작업 제출 거절: reason={}", e.getClass().getSimpleName());
      store.releaseReminderSend(email, slot.requestId());
    }
  }

  // 메일 스레드에서 실행: SMTP 실패면 이 요청의 예약만 되돌려 다시 요청할 수 있게 함
  // (되돌리기는 요청 식별자 기준이라 늦게 실패해도 그 사이 다른 요청의 재발송 제한 · 하루 횟수는 지우지 않음)
  // SMTP 실패만 정리한다: 그 밖의 예외(지원하지 않는 로그인 방식 = 코드 오류)와 되돌리기 중 Redis 오류는 작업 밖으로 전파
  private void sendReminderMail(String email, Provider provider, String requestId) {
    try {
      mailSender.send(email, provider);
    } catch (MailException e) {
      // 예외 메시지 · 스택 트레이스에 SMTP 응답(수신 주소 포함 가능)이 들어갈 수 있어, 이메일과 예외 객체 없이 예외 종류만 남김
      log.warn("가입 계정 안내 메일 발송 실패: cause={}", e.getClass().getSimpleName());
      store.releaseReminderSend(email, requestId);
    }
  }

  private static boolean remindable(Member member) {
    return member.getStatus() == MemberStatus.ACTIVE && SUPPORTED_PROVIDERS.contains(member.getProvider());
  }
}
