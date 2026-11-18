package com.justcommit.backend.member.withdrawal.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.member.auth.application.EmailSendResult;
import com.justcommit.backend.member.auth.application.EmailVerificationService;
import com.justcommit.backend.member.auth.domain.VerificationBinding;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.MemberStatus;
import com.justcommit.backend.member.domain.Provider;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.withdrawal.infrastructure.redis.WithdrawalTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 탈퇴 재확인 - 카카오 회원 (#407). 계좌 변경(AccountChangeService)과 같은 흐름으로 이메일 재인증 → 일회용 탈퇴 토큰
 * - 로컬 회원은 최종 탈퇴 요청에서 현재 비밀번호로 재확인하므로 이 경로를 쓰지 않는다 (400)
 * - 토큰: 32바이트 난수(Base64url). Redis에 해시로, 발송 당시 회원 id · 인증 버전과 함께 10분 저장
 * - 토큰의 실제 사용(확인 · 꺼내기)은 최종 탈퇴 요청에서 한다
 */
@Service
@RequiredArgsConstructor
public class WithdrawalVerificationService {

  static final Duration WITHDRAWAL_TOKEN_TTL = Duration.ofMinutes(10);
  private static final int TOKEN_BYTES = 32; // 256비트 랜덤
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final MemberRepository memberRepository;
  private final EmailVerificationService emailVerificationService;
  private final WithdrawalTokenStore tokenStore;

  // 탈퇴 인증번호 발송: 활동 중인 카카오 회원만 (활동 중이 아니면 404, 로컬 회원이면 400)
  public EmailSendResult sendVerificationCode(Long memberId) {
    Member member = findKakaoMember(memberId);
    if (member.getStatus() != MemberStatus.ACTIVE) {
      throw new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND);
    }
    VerificationBinding binding = new VerificationBinding(member.getId(), member.getTokenVersion());
    return emailVerificationService.sendWithdrawalCode(member.getEmail(), binding);
  }

  // 인증번호 확인 → 탈퇴 토큰 발급
  // 번호가 틀림 · 만료 · 오답 한도면 각 오류, 번호는 맞지만 발송 당시와 회원 · 인증 버전 · 상태가 다르면 WITHDRAWAL_VERIFICATION_INVALID
  public WithdrawalTokenResult issueToken(Long memberId, String code) {
    Member member = findKakaoMember(memberId);
    // 탈퇴한 회원은 이메일이 NULL이라, 이메일을 쓰기 전에 상태부터 확인한다
    if (member.getStatus() != MemberStatus.ACTIVE) {
      throw new BusinessException(MemberErrorCode.WITHDRAWAL_VERIFICATION_INVALID);
    }
    VerificationBinding binding = emailVerificationService.verifyWithdrawalCode(member.getEmail(), code)
        .filter(sent -> sent.memberId().equals(member.getId()) && sent.tokenVersion() == member.getTokenVersion())
        .orElseThrow(() -> new BusinessException(MemberErrorCode.WITHDRAWAL_VERIFICATION_INVALID));
    String token = newToken();
    tokenStore.save(token, binding, WITHDRAWAL_TOKEN_TTL);
    return new WithdrawalTokenResult(token, WITHDRAWAL_TOKEN_TTL.toSeconds());
  }

  // 회원이 없으면 404, 카카오 회원이 아니면 400 (로컬 회원은 비밀번호로 재확인)
  private Member findKakaoMember(Long memberId) {
    Member member = memberRepository.findById(memberId)
        .orElseThrow(() -> new BusinessException(MemberErrorCode.MEMBER_NOT_FOUND));
    if (member.getProvider() != Provider.KAKAO) {
      throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA);
    }
    return member;
  }

  private static String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    SECURE_RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
