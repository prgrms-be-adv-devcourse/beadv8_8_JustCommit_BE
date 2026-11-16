package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.infrastructure.mail.VerificationMailSender;
import com.justcommit.backend.member.auth.infrastructure.redis.EmailVerificationRedisStore;
import com.justcommit.backend.member.domain.exception.EmailCooldownException;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

// 이메일 인증번호 발송·확인
// #412: 목적(가입 · 비밀번호 재설정 · 계좌 변경)별로 인증을 나누고, 같은 이메일 하루 발송 한도 · 틀린 횟수 제한을 둔다
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {
  // 정책 값: Redis TTL과 응답의 남은 시간을 같은 상수에서 가져옴
  static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
  static final Duration CODE_TTL = Duration.ofMinutes(5);
  static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
  // 같은 이메일 하루 발송 한도 (목적과 관계없이, 첫 발송부터 24시간)
  static final int DAILY_SEND_LIMIT = 5;
  static final Duration DAILY_WINDOW = Duration.ofDays(1);
  // 이만큼 틀리면 인증번호를 폐기 (다시 받으려면 재발송)
  static final int MAX_WRONG_ATTEMPTS = 5;
  // 예측 불가 난수 (보안용)
  private static final SecureRandom RANDOM = new SecureRandom();
  private final EmailVerificationRedisStore store;
  private final VerificationMailSender mailSender;
  private final MemberRepository memberRepository;

  // 인증번호 발송: 목적 확인 -> 가입 여부 확인 -> 재발송 제한 · 하루 한도 확인 -> 번호 생성, 저장 -> 메일 발송
  public EmailSendResult sendCode(String rawEmail, EmailPurpose purpose) {
    requireSupported(purpose);
    String email = normalize(rawEmail);
    // 이미 가입된 이메일이면 인증번호를 보내지 않음 (가입 목적)
    if (memberRepository.existsByEmail(email)) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_EMAIL);
    }
    EmailVerificationRedisStore.SendResult slot = acquireSendSlot(email);
    String code = generateCode();
    if (!store.saveCode(purpose, email, slot.requestId(), code, CODE_TTL)) {
      // 자리를 잡은 뒤 멈춘 사이 재발송 제한이 끝나 다른 요청이 새 번호를 보낸 경우:
      // 그 번호를 덮어쓰지 않고 메일도 보내지 않는다 (이 요청의 하루 횟수만 되돌림)
      store.releaseSend(purpose, email, slot.requestId());
      throw new EmailCooldownException(COOLDOWN_TTL.toSeconds());
    }
    try {
      mailSender.send(email, code, CODE_TTL);
    } catch (MailException e) {
      // 발송 실패 시 이 요청이 만든 번호 · 재발송 제한 · 하루 횟수만 되돌려 다시 시도할 수 있게 함
      // (늦게 실패해도 그 사이 다른 요청이 만든 번호 · 재발송 제한은 지우지 않음, 서버 쪽 실패는 하루 횟수에 넣지 않음)
      log.error("인증 메일 발송 실패: email={}", email, e);
      store.releaseSend(purpose, email, slot.requestId());
      throw new BusinessException(MemberErrorCode.EMAIL_SEND_FAILED);
    }
    return new EmailSendResult(CODE_TTL.toSeconds(), COOLDOWN_TTL.toSeconds());
  }

  // 인증번호 확인: 비교 · 틀린 횟수 · 폐기를 Redis에서 한 번에 처리하고, 일치하면 그 목적의 인증 완료를 저장
  public void verifyCode(String rawEmail, String code, EmailPurpose purpose) {
    requireSupported(purpose);
    String email = normalize(rawEmail);
    switch (store.verifyCode(purpose, email, code, MAX_WRONG_ATTEMPTS, CODE_TTL)) {
      case MATCHED -> store.saveVerified(purpose, email, VERIFIED_TTL);
      case MISMATCH -> throw new BusinessException(MemberErrorCode.EMAIL_CODE_MISMATCH);
      case EXHAUSTED -> throw new BusinessException(MemberErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);
      case EXPIRED -> throw new BusinessException(MemberErrorCode.EMAIL_CODE_EXPIRED);
    }
  }

  // 재발송 제한(60초) · 하루 한도(5회)를 확인하고 자리를 확보. 막히면 429 + 남은 초(Retry-After)
  private EmailVerificationRedisStore.SendResult acquireSendSlot(String email) {
    EmailVerificationRedisStore.SendResult result =
        store.tryAcquireSend(email, COOLDOWN_TTL, DAILY_SEND_LIMIT, DAILY_WINDOW);
    switch (result.status()) {
      case ACQUIRED -> {
      }
      case COOLDOWN -> throw new EmailCooldownException(result.retryAfterSeconds());
      case DAILY_LIMIT -> throw new EmailCooldownException(
          MemberErrorCode.EMAIL_SEND_DAILY_LIMIT, result.retryAfterSeconds());
    }
    return result;
  }

  // TODO: #412 비밀번호 재설정에서 PASSWORD_RESET, #413 계좌 변경에서 ACCOUNT_CHANGE를 연다
  private void requireSupported(EmailPurpose purpose) {
    if (purpose != EmailPurpose.SIGNUP) {
      throw new BusinessException(MemberErrorCode.EMAIL_PURPOSE_NOT_SUPPORTED);
    }
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
