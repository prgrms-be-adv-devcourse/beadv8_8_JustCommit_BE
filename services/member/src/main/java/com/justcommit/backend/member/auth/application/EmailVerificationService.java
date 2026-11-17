package com.justcommit.backend.member.auth.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.auth.domain.EmailPurpose;
import com.justcommit.backend.member.auth.domain.EmailSendQuota;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
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
import java.util.Optional;

// 이메일 인증번호 발송·확인
// #412: 목적(가입 · 비밀번호 재설정 · 계좌 변경)별로 인증을 나누고, 같은 이메일 하루 발송 한도 · 틀린 횟수 제한을 둔다
// #413: 계좌 변경은 로그인 필수 전용 메서드(sendAccountChangeCode · verifyAccountChangeCode)로만 쓴다
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {
  // 인증번호 정책 값 (발송 간격 · 하루 한도는 안내 메일과 함께 쓰는 EmailSendPolicy)
  static final Duration CODE_TTL = Duration.ofMinutes(5);
  static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
  // 이만큼 틀리면 인증번호를 폐기 (다시 받으려면 재발송)
  static final int MAX_WRONG_ATTEMPTS = 5;
  // 예측 불가 난수 (보안용)
  private static final SecureRandom RANDOM = new SecureRandom();
  private final EmailVerificationRedisStore store;
  private final VerificationMailSender mailSender;
  private final MemberRepository memberRepository;
  private final PasswordResetService passwordResetService;

  // 인증번호 발송 (공개 경로): 목적 확인 -> 가입 여부 확인 -> 번호 발송
  public EmailSendResult sendCode(String rawEmail, EmailPurpose purpose) {
    requireSupported(purpose);
    String email = normalize(rawEmail);
    if (purpose == EmailPurpose.PASSWORD_RESET) {
      sendResetCode(email);
      return new EmailSendResult(CODE_TTL.toSeconds(), EmailSendPolicy.COOLDOWN.toSeconds());
    }
    // 이미 가입된 이메일이면 인증번호를 보내지 않음 (가입 목적)
    if (memberRepository.existsByEmail(email)) {
      throw new BusinessException(MemberErrorCode.DUPLICATE_EMAIL);
    }
    return issueCode(email, purpose, "");
  }

  // 인증번호 확인 (공개 경로): 비교 · 틀린 횟수 · 폐기를 Redis에서 한 번에 처리하고, 일치하면 그 목적의 인증 완료를 저장
  // 재설정 목적은 인증 완료 표시 대신 일회용 재설정 토큰을 돌려준다 (가입은 빈 결과)
  public EmailVerifyResult verifyCode(String rawEmail, String code, EmailPurpose purpose) {
    requireSupported(purpose);
    String email = normalize(rawEmail);
    EmailVerificationRedisStore.VerifyOutcome outcome =
        store.verifyCode(purpose, email, code, MAX_WRONG_ATTEMPTS, CODE_TTL);
    if (purpose == EmailPurpose.PASSWORD_RESET) {
      return EmailVerifyResult.resetToken(issueResetToken(outcome), PasswordResetService.RESET_TOKEN_TTL);
    }
    requireMatched(outcome.result());
    store.saveVerified(purpose, email, VERIFIED_TTL);
    return EmailVerifyResult.none();
  }

  // 계좌 변경 인증번호 발송 (#413): 로그인한 회원의 이메일로 보내고, 발송 당시 회원 id · 인증 버전을 번호에 묶는다
  // 로그인한 본인 요청이라 가입처럼 재발송 제한 · 하루 한도를 429로 알린다
  public EmailSendResult sendAccountChangeCode(String email, VerificationBinding binding) {
    return issueCode(email, EmailPurpose.ACCOUNT_CHANGE, binding.toBinding());
  }

  // 계좌 변경 인증번호 확인 (#413): 틀림 · 만료 · 오답 한도는 가입처럼 각 오류로 알리고,
  // 맞으면 발송 때 묶은 회원 정보를 돌려준다 (깨졌으면 빈 값). 인증 완료 상태는 따로 저장하지 않는다
  public Optional<VerificationBinding> verifyAccountChangeCode(String email, String code) {
    EmailVerificationRedisStore.VerifyOutcome outcome =
        store.verifyCode(EmailPurpose.ACCOUNT_CHANGE, email, code, MAX_WRONG_ATTEMPTS, CODE_TTL);
    requireMatched(outcome.result());
    return VerificationBinding.fromBinding(outcome.binding());
  }

  // 비밀번호 재설정 인증번호 발송 (#412): 가입 여부 · 로그인 방식이 드러나지 않게 모든 경우 같은 결과로 끝낸다
  // - 재발송 제한 · 하루 한도: 자리를 못 잡았으니 되돌릴 것 없이 끝
  // - 자리는 재설정 대상인지와 관계없이 똑같이 잡는다 (하루 횟수도 똑같이 세어, 한도에 걸리는지로 가입 여부를 알 수 없게)
  // - 대상이면 발송 당시 회원 id · 인증 버전을 번호에 묶어 저장하고 재설정 문구로 메일
  // - 번호 저장 거절(다른 요청이 자리를 잡음) · SMTP 실패: 이 요청의 예약만 되돌리고 끝
  // - Redis · DB 장애는 감추지 않는다 (예외가 그대로 올라가 서버 오류로 처리됨)
  private void sendResetCode(String email) {
    EmailVerificationRedisStore.SendResult slot = store.tryAcquireSend(EmailSendQuota.PASSWORD_RESET, email,
        EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW);
    if (slot.status() != EmailVerificationRedisStore.SendStatus.ACQUIRED) {
      return;
    }
    Optional<VerificationBinding> target = passwordResetService.findTarget(email);
    if (target.isEmpty()) {
      return;
    }
    String code = generateCode();
    if (!store.saveCode(EmailPurpose.PASSWORD_RESET, email, slot.requestId(), code, target.get().toBinding(), CODE_TTL)) {
      store.releaseSend(EmailPurpose.PASSWORD_RESET, email, slot.requestId());
      return;
    }
    try {
      mailSender.send(email, code, CODE_TTL, EmailPurpose.PASSWORD_RESET);
    } catch (MailException e) {
      // 예외 메시지 · 스택 트레이스에 SMTP 응답(수신 주소 포함 가능)이 들어갈 수 있어, 이메일과 예외 객체 없이 예외 종류만 남김
      log.error("재설정 인증 메일 발송 실패: cause={}", e.getClass().getSimpleName());
      store.releaseSend(EmailPurpose.PASSWORD_RESET, email, slot.requestId());
    }
  }

  // 재설정 인증번호 확인 (#412): 틀림 · 만료 · 폐기 · 깨진 묶음 정보 · 발송 뒤 인증 버전 변경은 모두 같은 오류
  // (어느 경우인지 응답으로 드러나지 않게). 번호가 맞고 발송 당시 상태 그대로일 때만 토큰을 받는다
  private String issueResetToken(EmailVerificationRedisStore.VerifyOutcome outcome) {
    if (outcome.result() != EmailVerificationRedisStore.VerifyResult.MATCHED) {
      throw new BusinessException(MemberErrorCode.EMAIL_CODE_INVALID);
    }
    return VerificationBinding.fromBinding(outcome.binding())
        .flatMap(passwordResetService::issueToken)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.EMAIL_CODE_INVALID));
  }

  // 번호 발송 (가입 · 계좌 변경 공통): 자리 확보 -> 번호 생성, 저장(binding을 함께) -> 메일 발송
  // 재설정은 결과를 숨겨야 해서 이 메서드를 쓰지 않는다 (sendResetCode)
  private EmailSendResult issueCode(String email, EmailPurpose purpose, String binding) {
    EmailVerificationRedisStore.SendResult slot = acquireSendSlot(email, purpose);
    String code = generateCode();
    if (!store.saveCode(purpose, email, slot.requestId(), code, binding, CODE_TTL)) {
      // 자리를 잡은 뒤 멈춘 사이 재발송 제한이 끝나 다른 요청이 새 번호를 보낸 경우:
      // 그 번호를 덮어쓰지 않고 메일도 보내지 않는다 (이 요청의 하루 횟수만 되돌림)
      store.releaseSend(purpose, email, slot.requestId());
      throw new EmailCooldownException(EmailSendPolicy.COOLDOWN.toSeconds());
    }
    try {
      mailSender.send(email, code, CODE_TTL, purpose);
    } catch (MailException e) {
      // 발송 실패 시 이 요청이 만든 번호 · 재발송 제한 · 하루 횟수만 되돌려 다시 시도할 수 있게 함
      // (늦게 실패해도 그 사이 다른 요청이 만든 번호 · 재발송 제한은 지우지 않음, 서버 쪽 실패는 하루 횟수에 넣지 않음)
      // 예외 메시지 · 스택 트레이스에 SMTP 응답(수신 주소 포함 가능)이 들어갈 수 있어, 이메일과 예외 객체 없이 예외 종류만 남김
      log.error("인증 메일 발송 실패: purpose={}, cause={}", purpose, e.getClass().getSimpleName());
      store.releaseSend(purpose, email, slot.requestId());
      throw new BusinessException(MemberErrorCode.EMAIL_SEND_FAILED);
    }
    return new EmailSendResult(CODE_TTL.toSeconds(), EmailSendPolicy.COOLDOWN.toSeconds());
  }

  // 번호 확인 결과가 일치가 아니면 각 오류로 (가입 · 계좌 변경 공통. 재설정은 하나의 오류로 숨기므로 쓰지 않음)
  private void requireMatched(EmailVerificationRedisStore.VerifyResult result) {
    switch (result) {
      case MATCHED -> {
      }
      case MISMATCH -> throw new BusinessException(MemberErrorCode.EMAIL_CODE_MISMATCH);
      case EXHAUSTED -> throw new BusinessException(MemberErrorCode.EMAIL_CODE_ATTEMPTS_EXCEEDED);
      case EXPIRED -> throw new BusinessException(MemberErrorCode.EMAIL_CODE_EXPIRED);
    }
  }

  // 재발송 제한(60초, 이메일 공유) · 하루 한도(5회, 용도별)를 확인하고 자리를 확보. 막히면 429 + 남은 초(Retry-After)
  // 하루 한도는 복구(releaseSend(purpose, ...))와 같은 기준인 EmailSendQuota.of(purpose)
  private EmailVerificationRedisStore.SendResult acquireSendSlot(String email, EmailPurpose purpose) {
    EmailVerificationRedisStore.SendResult result = store.tryAcquireSend(EmailSendQuota.of(purpose), email,
        EmailSendPolicy.COOLDOWN, EmailSendPolicy.DAILY_LIMIT, EmailSendPolicy.DAILY_WINDOW);
    switch (result.status()) {
      case ACQUIRED -> {
      }
      case COOLDOWN -> throw new EmailCooldownException(result.retryAfterSeconds());
      case DAILY_LIMIT -> throw new EmailCooldownException(
          MemberErrorCode.EMAIL_SEND_DAILY_LIMIT, result.retryAfterSeconds());
    }
    return result;
  }

  // 공개 경로(sendCode · verifyCode)에서는 계좌 변경을 받지 않는다 (로그인 필수 전용 메서드로만, #413)
  private void requireSupported(EmailPurpose purpose) {
    if (purpose == EmailPurpose.ACCOUNT_CHANGE) {
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
