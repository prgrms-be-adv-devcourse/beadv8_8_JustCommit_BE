package com.justcommit.backend.member.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.infrastructure.redis.EmailVerificationRedisStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

// 이메일 인증번호 발송·확인
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

  // 정책 값: Redix TTL과 응답의 남은 시간을 같은 상수에서 가져옴
  static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
  static final Duration CODE_TTL = Duration.ofMinutes(5);
  static final Duration VERIFIED_TTL = Duration.ofMinutes(30);

  // 예측 불가 난수 (보안용)
  private static final SecureRandom RANDOM = new SecureRandom();

  private final EmailVerificationRedisStore store;
  private final VerificationMailSender mailSender;

  // 인증번호 발송: 재발송 제한 확인 -> 번호 생성, 저장 -> 메일 발송
  public EmailSendResult sendCode(String rawEmail) {
    String email = normalize(rawEmail);

    if (!store.tryStartCooldown(email, COOLDOWN_TTL)) {
      throw new EmailCooldownException(store.getCooldownRemainingSeconds(email));
    }

    String code = generateCode();
    store.saveCode(email, code, CODE_TTL);

    try {
      mailSender.send(email, code, CODE_TTL);
    } catch (MailException e) {
      // 발송 실패 시 저장한 값을 되돌려서 다시 시도할 수 있게 함
      log.error("인증 메일 발송 실패: email={}", email, e);
      store.deleteCode(email);
      store.deleteCooldown(email);
      throw new BusinessException(MemberErrorCode.EMAIL_SEND_FAILED);
    }

    return new EmailSendResult(CODE_TTL.toSeconds(), COOLDOWN_TTL.toSeconds());
  }

  // 인증번호 확인: 일치하면 번호 삭제 + 인증 완료 저장
  public void verifyCode(String rawEmail, String code) {
    String email = normalize(rawEmail);

    String savedCode = store.findCode(email)
            .orElseThrow(() -> new BusinessException(MemberErrorCode.EMAIL_CODE_EXPIRED));

    if (!savedCode.equals(code)) {
      throw new BusinessException(MemberErrorCode.EMAIL_CODE_MISMATCH);
    }

    store.deleteCode(email);  // 같은 번호로 다시 인증x
    store.saveVerified(email, VERIFIED_TTL);
  }

  // 대소문자,앞뒤 공백 차이로 다른 키가 생기지 않게
  private String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  // 000000 ~ 999999
  private String generateCode() {
    return String.format("%06d", RANDOM.nextInt(1_000_000));
  }
}
