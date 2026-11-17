package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.member.auth.domain.EmailSendQuota;
import com.justcommit.backend.member.auth.infrastructure.mail.AccountReminderMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * - 메일 발송만 감싸 SMTP 실패(MailException)일 때 이 요청의 예약을 되돌린다 (되돌리다 Redis 오류가 나면 그대로 올라감)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountReminderService {

  // 지금 안내할 수 있는 로그인 방식 (네이버는 로그인 기능이 열릴 때 안내 문구와 함께 추가)
  private static final Set<Provider> SUPPORTED_PROVIDERS = EnumSet.of(Provider.LOCAL, Provider.KAKAO);

  private final EmailVerificationRedisStore store;
  private final MemberRepository memberRepository;
  private final AccountReminderMailSender mailSender;

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
    try {
      mailSender.send(email, target.get().getProvider());
    } catch (MailException e) {
      log.error("가입 계정 안내 메일 발송 실패: email={}", email, e);
      store.releaseReminderSend(email, slot.requestId());
    }
  }

  private static boolean remindable(Member member) {
    return member.getStatus() == MemberStatus.ACTIVE && SUPPORTED_PROVIDERS.contains(member.getProvider());
  }
}
