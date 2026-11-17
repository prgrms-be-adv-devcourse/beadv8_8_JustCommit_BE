package com.justcommit.backend.member.account.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.member.account.infrastructure.AccountChangeTokenStore;
import com.justcommit.backend.member.auth.application.EmailSendResult;
import com.justcommit.backend.member.auth.application.EmailVerificationService;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 계좌 변경 (#413). 로그인한 회원이 이메일 재인증으로 일회용 계좌 변경 토큰을 받는다
 * - 인증번호는 회원 정보의 이메일로 보내고, 발송 당시 회원 id · 인증 버전을 번호에 묶는다
 * - 인증번호가 맞으면 묶어 둔 회원 id · 인증 버전을 지금 회원과 비교한다.
 *   다르거나(인증번호를 받은 뒤 비밀번호 변경 등) 활동 중이 아니면 토큰을 발급하지 않는다
 * - 토큰: 32바이트 난수(Base64url). Redis에 해시로, 발송 당시 회원 id · 인증 버전과 함께 10분 저장
 */
@Service
@RequiredArgsConstructor
public class AccountChangeService {

  static final Duration CHANGE_TOKEN_TTL = Duration.ofMinutes(10);
  private static final int CHANGE_TOKEN_BYTES = 32; // 256비트 랜덤
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final MemberRepository memberRepository;
  private final EmailVerificationService emailVerificationService;
  private final AccountChangeTokenStore tokenStore;

  // 계좌 변경 인증번호 발송: 활동 중인 회원만 (아니면 404 MEMBER_NOT_FOUND)
  public EmailSendResult sendVerificationCode(Long memberId) {
    Member member = memberRepository.findById(memberId)
        .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    VerificationBinding binding = new VerificationBinding(member.getId(), member.getTokenVersion());
    return emailVerificationService.sendAccountChangeCode(member.getEmail(), binding);
  }

  // 인증번호 확인 → 계좌 변경 토큰 발급
  // 번호가 틀림 · 만료 · 오답 한도면 각 오류, 번호는 맞지만 발송 당시와 회원 · 인증 버전 · 상태가 다르면 ACCOUNT_VERIFICATION_INVALID
  public AccountChangeTokenResult issueChangeToken(Long memberId, String code) {
    Member member = memberRepository.findById(memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    VerificationBinding binding = emailVerificationService.verifyAccountChangeCode(member.getEmail(), code)
        .filter(sent -> stillValid(sent, member))
        .orElseThrow(() -> new BusinessException(MemberErrorCode.ACCOUNT_VERIFICATION_INVALID));
    String token = newToken();
    tokenStore.save(token, binding, CHANGE_TOKEN_TTL);
    return new AccountChangeTokenResult(token, CHANGE_TOKEN_TTL.toSeconds());
  }

  // 발송 당시와 같은 회원 · 같은 인증 버전이고, 지금도 활동 중인지
  private static boolean stillValid(VerificationBinding sent, Member member) {
    return sent.memberId().equals(member.getId())
        && sent.tokenVersion() == member.getTokenVersion()
        && member.getStatus() == MemberStatus.ACTIVE;
  }

  private static String newToken() {
    byte[] bytes = new byte[CHANGE_TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
